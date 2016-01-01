package geotrellis.spark.buffer

import geotrellis.spark._
import geotrellis.raster._
import geotrellis.raster.crop._
import geotrellis.raster.stitch._

import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scala.reflect.ClassTag
import scala.collection.mutable.ArrayBuffer

object BufferTiles {
  sealed trait Direction

  case object Center extends Direction
  case object Top extends Direction
  case object TopRight extends Direction
  case object Right extends Direction
  case object BottomRight extends Direction
  case object Bottom extends Direction
  case object BottomLeft extends Direction
  case object Left extends Direction
  case object TopLeft extends Direction

  def collectWithBorders[K: SpatialComponent, V <: CellGrid: (? => CropMethods[V])](
    key: K, 
    tile: V, 
    includeKey: SpatialKey => Boolean,
    getBorderSizes: SpatialKey => BorderSizes
  ): Seq[(K, (Direction, V))] = {
    val SpatialKey(col, row) = key.spatialComponent
    val parts = new ArrayBuffer[(K, (Direction, V))](9)

    val cols = tile.cols
    val rows = tile.rows

    // ex: adding "TopLeft" corner of this tile to contribute to "TopLeft" tile at key
    def addSlice(spatialKey: SpatialKey, direction: => Direction) {
      if(includeKey(spatialKey)) {
        val borderSizes = getBorderSizes(spatialKey)

        val part: V =
          direction match {
            case Center => tile
            case Right => tile.crop(0, 0, borderSizes.right - 1, rows - 1)(force = true)
            case Left => tile.crop(cols - borderSizes.left, 0, cols - 1, rows - 1)(force = true)
            case Top => tile.crop(0, rows - borderSizes.top, cols - 1, rows - 1)(force = true)
            case Bottom => tile.crop(0, 0, cols - 1, borderSizes.bottom - 1)(force = true)
            case TopLeft => tile.crop(cols - borderSizes.left, rows - borderSizes.top, cols - 1, rows - 1)(force = true)
            case TopRight => tile.crop(0, rows - borderSizes.top, borderSizes.right - 1, rows - 1)(force = true)
            case BottomLeft => tile.crop(cols - borderSizes.left, 0, cols - 1, borderSizes.bottom - 1)(force = true)
            case BottomRight => tile.crop(0, 0, borderSizes.right - 1, borderSizes.bottom - 1)(force = true)
          }

        parts += ( (key.updateSpatialComponent(spatialKey), (direction, part)) )
      }
    }

    // ex: A tile that contributes to the top (tile above it) will give up it's top slice, which will be placed at the bottom of the target focal window
    addSlice(SpatialKey(col,row), Center)

    addSlice(SpatialKey(col-1, row), Right)
    addSlice(SpatialKey(col+1, row), Left)
    addSlice(SpatialKey(col, row-1), Bottom)
    addSlice(SpatialKey(col, row+1), Top)

    addSlice(SpatialKey(col-1, row-1), BottomRight)
    addSlice(SpatialKey(col+1, row-1), BottomLeft)
    addSlice(SpatialKey(col+1, row+1), TopLeft)
    addSlice(SpatialKey(col-1, row+1), TopRight)

    parts
  }

  def apply[
    K: SpatialComponent: ClassTag,
    V <: CellGrid: Stitcher: ClassTag: (? => CropMethods[V])
  ](rdd: RDD[(K, V)], borderSize: Int): RDD[(K, BufferedTile[V])] =
    apply(rdd, borderSize, GridBounds(Int.MinValue, Int.MinValue, Int.MaxValue, Int.MaxValue))

  def apply[
    K: SpatialComponent: ClassTag,
    V <: CellGrid: Stitcher: ClassTag: (? => CropMethods[V])
  ](rdd: RDD[(K, V)], borderSize: Int, layerBounds: GridBounds): RDD[(K, BufferedTile[V])] = {
    val borderSizes = BorderSizes(borderSize, borderSize, borderSize, borderSize)
    val tilesAndSlivers =
      rdd
        .flatMap { case (key, tile) => 
          collectWithBorders(key, tile, { key => layerBounds.contains(key.col, key.row) }, { key => borderSizes })
        }

    val grouped =
      rdd.partitioner match {
        case Some(partitioner) => tilesAndSlivers.groupByKey(partitioner)
        case None => tilesAndSlivers.groupByKey
      }

    grouped
      .flatMapValues { neighbors =>
        neighbors.find( _._1 == Center) map { case (_, tile) =>
          val cols = tile.cols
          val rows = tile.rows

          val pieces =
            neighbors.map { case (direction, slice) =>
              val (updateColMin, updateRowMin) =
                direction match {
                  case Center      => (borderSize, borderSize)
                  case Left       => (0, borderSize)
                  case Right        => (borderSize + cols, borderSize)
                  case Top      => (borderSize, 0)
                  case Bottom         => (borderSize, borderSize + rows)
                  case TopLeft => (0, 0)
                  case TopRight  => (borderSize + cols, 0)
                  case BottomLeft    => (0, borderSize + rows)
                  case BottomRight     => (borderSize + cols, borderSize + rows)
                }

              (slice, (updateColMin, updateRowMin))
          }

          val focalTile = implicitly[Stitcher[V]].stitch(pieces, cols + (2 * borderSize), rows + (2 * borderSize))
          BufferedTile(focalTile, GridBounds(borderSize, borderSize, borderSize+cols-1, borderSize+rows-1))
        }
    }
  }

  def apply[
    K: SpatialComponent: ClassTag,
    V <: CellGrid: Stitcher: ClassTag: (? => CropMethods[V])
  ](rdd: RDD[(K, V)], getBorderSizes: K => BorderSizes): RDD[(K, BufferedTile[V])] = {
    val borderSizesPerKey =
      rdd
        .mapPartitions({ partition =>
          partition.map { case (key, _) => (key, getBorderSizes(key)) }
        }, preservesPartitioning = true)
        .persist(StorageLevel.MEMORY_ONLY)
 
    val result = apply(rdd, borderSizesPerKey)
    borderSizesPerKey.unpersist(blocking = false)
    result
  }

  def apply[
    K: SpatialComponent: ClassTag,
    V <: CellGrid: Stitcher: ClassTag: (? => CropMethods[V])
  ](rdd: RDD[(K, V)], borderSizesPerKey: RDD[(K, BorderSizes)]): RDD[(K, BufferedTile[V])] = {
    val surroundingBorderSizes: RDD[(K, Map[SpatialKey, BorderSizes])] = {
      val contributingKeys =
        borderSizesPerKey
          .flatMap { case (key, borderSizes) =>
            val spatialKey @ SpatialKey(col, row) = key.spatialComponent
            Seq(
              (key, (spatialKey, borderSizes)),

              (key.updateSpatialComponent(SpatialKey(col-1, row)), (spatialKey, borderSizes)),
              (key.updateSpatialComponent(SpatialKey(col+1, row)), (spatialKey, borderSizes)),
              (key.updateSpatialComponent(SpatialKey(col, row-1)), (spatialKey, borderSizes)),
              (key.updateSpatialComponent(SpatialKey(col, row+1)), (spatialKey, borderSizes)),

              (key.updateSpatialComponent(SpatialKey(col-1, row-1)), (spatialKey, borderSizes)),
              (key.updateSpatialComponent(SpatialKey(col+1, row-1)), (spatialKey, borderSizes)),
              (key.updateSpatialComponent(SpatialKey(col+1, row+1)), (spatialKey, borderSizes)),
              (key.updateSpatialComponent(SpatialKey(col-1, row+1)), (spatialKey, borderSizes))
            )

          }

      val grouped = 
        rdd.partitioner match {
          case Some(partitioner) => contributingKeys.groupByKey(partitioner)
          case None => contributingKeys.groupByKey
        }

      grouped
        .mapValues { _.toMap }
    }

    val tilesAndSlivers =
      rdd
        .join(surroundingBorderSizes)
        .flatMap { case (key, (tile, borderSizesMap)) => 
          collectWithBorders(key, tile, borderSizesMap.contains _, borderSizesMap)
        }

    val grouped =
      rdd.partitioner match {
        case Some(partitioner) => tilesAndSlivers.groupByKey(partitioner)
        case None => tilesAndSlivers.groupByKey
      }

    grouped.join(borderSizesPerKey)
      .mapPartitions({ partition =>
        partition.flatMap { case (key, (neighbors, borderSizes)) =>
          neighbors.find( _._1 == Center) map { case (_, centerTile) =>
            val pieces =
              neighbors.map { case (direction, slice) =>
                val (updateColMin, updateRowMin) =
                  direction match {
                    case Center      => (borderSizes.top, borderSizes.left)
                    case Left        => (0, borderSizes.top)
                    case Right       => (borderSizes.left + centerTile.cols, borderSizes.top)
                    case Top         => (borderSizes.left, 0)
                    case Bottom      => (borderSizes.left, borderSizes.top + centerTile.rows)
                    case TopLeft     => (0, 0)
                    case TopRight    => (borderSizes.left + centerTile.cols, 0)
                    case BottomLeft  => (0, borderSizes.top + centerTile.rows)
                    case BottomRight => (borderSizes.left + centerTile.cols, borderSizes.top + centerTile.rows)
                  }

                (slice, (updateColMin, updateRowMin))
              }
            val cols = centerTile.cols + borderSizes.left + borderSizes.right
            val rows = centerTile.rows + borderSizes.top + borderSizes.bottom

            val stitched = implicitly[Stitcher[V]].stitch(pieces, cols, rows)

            (key, BufferedTile(stitched, GridBounds(borderSizes.left, borderSizes.top, cols - borderSizes.right - 1, rows - borderSizes.bottom - 1)))
          }
        }
      }, preservesPartitioning = true)
  }
}
