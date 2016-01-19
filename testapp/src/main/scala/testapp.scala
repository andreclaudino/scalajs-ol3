
import ol3.ol.source.MapQuest
import ol3.olx.ViewOptions
import ol3.olx.layer.TileOptions
import ol3.olx.source.MapQuestOptions

import scala.scalajs.js
import scala.scalajs.js._
import scala.scalajs.js.JSApp
import scala.scalajs.js.annotation.{JSName, JSExport}
import org.scalajs.dom
/**
 * Created by pappmar on 10/11/2015.
 */
object TestApp extends JSApp {
  import ol3._

  @JSExport
  override def main(): Unit = {
    import ol3.implicits._

    new ol.Map(olx.MapOptions(
      target = dom.document.getElementById("map"),
      layers = js.Array(
        new ol.layer.Tile(TileOptions(
          source = new MapQuest(MapQuestOptions(
            layer = "sat"
          ))
        ))
      ),
      view = new ol.View(ViewOptions(
        center = global.ol.proj.fromLonLat(js.Array(37.0, 8.0)),
        zoom = 4.0
      ))
    ))


  }
}


