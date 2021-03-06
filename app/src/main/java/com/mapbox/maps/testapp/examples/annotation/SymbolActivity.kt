package com.mapbox.maps.testapp.examples.annotation

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.eq
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.get
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.literal
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.not
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.toNumber
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.extension.style.utils.ColorUtils
import com.mapbox.maps.plugin.annotation.generated.*
import com.mapbox.maps.plugin.annotation.getAnnotationPlugin
import com.mapbox.maps.plugin.location.utils.BitmapUtils
import com.mapbox.maps.testapp.R
import com.mapbox.maps.testapp.utils.Assets
import kotlinx.android.synthetic.main.activity_add_marker_symbol.*
import kotlinx.android.synthetic.main.activity_add_marker_symbol.mapView
import kotlinx.android.synthetic.main.activity_annotation.*
import java.io.IOException
import java.util.*

/**
 * Example showing how to add Symbol annotations
 */
class SymbolActivity : AppCompatActivity() {
  private val random = Random()
  private var symbolManager: SymbolManager? = null
  private var symbol: Symbol? = null
  private val animators: MutableList<ValueAnimator> = mutableListOf()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_annotation)
    mapView.getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) { style ->
      BitmapUtils.getBitmapFromDrawable(
        ResourcesCompat.getDrawable(
          resources,
          R.drawable.ic_airplanemode_active_black_24dp,
          this@SymbolActivity.theme
        )
      )?.let {
        style.addImage(
          ID_ICON_AIRPORT,
          it, true
        )
      }

      val annotationPlugin = mapView.getAnnotationPlugin()
      symbolManager = annotationPlugin.getSymbolManager().apply {
        addClickListener(
          OnSymbolClickListener {
            Toast.makeText(this@SymbolActivity, "Click: $it", Toast.LENGTH_LONG).show()
            false
          }
        )

        addLongClickListener(
          OnSymbolLongClickListener {
            Toast.makeText(this@SymbolActivity, "LongClick: $it", Toast.LENGTH_LONG).show()
            false
          }
        )

        // set non data driven properties
        iconAllowOverlap = true
        textAllowOverlap = true

        // create a symbol
        val symbolOptions: SymbolOptions = SymbolOptions()
          .withPoint(Point.fromLngLat(0.381457, 6.687337))
          .withIconImage(ID_ICON_AIRPORT)
          .withIconSize(1.3)
          .withSymbolSortKey(10.0)
          .withDraggable(true)
        symbol = create(symbolOptions)

        // create nearby symbols
        val nearbyOptions: SymbolOptions = SymbolOptions()
          .withPoint(Point.fromLngLat(0.367099, 6.626384))
          .withIconImage(MAKI_ICON_CIRCLE)
          .withIconColor(ColorUtils.colorToRgbaString(Color.YELLOW))
          .withIconSize(2.5)
          .withSymbolSortKey(5.0)
          .withDraggable(true)
        create(nearbyOptions)

        // random add symbols across the globe
        val symbolOptionsList: MutableList<SymbolOptions> = ArrayList()
        for (i in 0..20) {
          symbolOptionsList.add(
            SymbolOptions()
              .withPoint(createRandomPoints())
              .withIconImage(MAKI_ICON_CAR)
              .withDraggable(true)
          )
        }
        create(symbolOptionsList)

        try {
          create(
            FeatureCollection.fromJson(
              Assets.loadStringFromAssets(
                this@SymbolActivity,
                "annotations.json"
              )
            )
          )
        } catch (e: IOException) {
          throw RuntimeException("Unable to parse annotations.json")
        }
      }
    }

    deleteAll.setOnClickListener { symbolManager?.deleteAll() }
  }

  override fun onCreateOptionsMenu(menu: Menu?): Boolean {
    menuInflater.inflate(R.menu.menu_symbol, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.menu_action_draggable -> {
        symbolManager?.annotations?.forEach {
          it.value.isDraggable = !it.value.isDraggable
        }
      }
      R.id.menu_action_filter -> {
        if (symbolManager != null && symbol != null) {
          val idKey = symbolManager!!.getAnnotationIdKey()
          val expression: Expression = eq(toNumber(get(idKey)), literal(symbol!!.id.toDouble()))
          val filter = symbolManager!!.layerFilter
          if (filter != null && filter == expression) {
            symbolManager!!.layerFilter = not(eq(toNumber(get(idKey)), literal(-1)))
          } else {
            symbolManager!!.layerFilter = expression
          }
        }
      }
      R.id.menu_action_icon -> symbol?.iconImage = MAKI_ICON_CAFE
      R.id.menu_action_rotation -> symbol?.iconRotate = 45.0
      R.id.menu_action_text -> symbol?.textField = "Hello world!"
      R.id.menu_action_anchor -> symbol?.iconAnchor = IconAnchor.BOTTOM
      R.id.menu_action_opacity -> symbol?.iconOpacity = 0.5
      R.id.menu_action_offset -> symbol?.iconOffset = listOf(10.0, 20.0)
      R.id.menu_action_text_anchor -> symbol?.textAnchor = TextAnchor.TOP
      R.id.menu_action_text_color -> symbol?.textColorInt = Color.WHITE
      R.id.menu_action_text_size -> symbol?.textSize = 22.0
      R.id.menu_action_z_index -> symbol?.symbolSortKey = 0.0
      R.id.menu_action_halo -> {
        symbol?.iconHaloWidth = 5.0
        symbol?.iconHaloColorInt = Color.RED
        symbol?.iconHaloBlur = 1.0
      }

      R.id.menu_action_animate -> {
        resetSymbol()
        symbol?.let { easeSymbol(it, Point.fromLngLat(6.687337, 0.381457), 180.0) }
        return true
      }
      else -> return super.onOptionsItemSelected(item)
    }
    symbol?.let { symbolManager?.update(it) }
    return true
  }

  private fun resetSymbol() {
    symbol?.iconRotate = 0.0
    symbol?.geometry = Point.fromLngLat(0.381457, 6.687337)
    symbol?.let { symbolManager?.update(it) }
  }

  private fun easeSymbol(symbol: Symbol, location: Point, rotation: Double) {
    val originalPosition: Point = symbol.point
    val originalRotation = symbol.iconRotate
    if (originalPosition == location || originalRotation == rotation) {
      return
    }
    val moveSymbol = ValueAnimator.ofFloat(0f, 1f).setDuration(5000)
    moveSymbol.interpolator = LinearInterpolator()
    moveSymbol.addUpdateListener { animation: ValueAnimator ->
      symbolManager?.let {
        if (it.annotations.values.indexOf(symbol) < 0) {
          return@addUpdateListener
        }
        val fraction = animation.animatedValue as Float
        if (originalPosition != location) {
          val lat =
            (location.latitude() - originalPosition.latitude()) * fraction + originalPosition.latitude()
          val lng =
            (location.longitude() - originalPosition.longitude()) * fraction + originalPosition.longitude()
          symbol.geometry = Point.fromLngLat(lng, lat)
        }
        if (originalRotation != null && originalRotation != rotation) {
          symbol.iconRotate = (rotation - originalRotation) * fraction + originalRotation
        }
        it.update(symbol)
      }
    }
    moveSymbol.start()
    animators.add(moveSymbol)
  }

  override fun onStart() {
    super.onStart()
    mapView.onStart()
  }

  override fun onStop() {
    super.onStop()
    animators.forEach { it.cancel() }
    mapView.onStop()
  }

  override fun onLowMemory() {
    super.onLowMemory()
    mapView.onLowMemory()
  }

  override fun onDestroy() {
    super.onDestroy()
    mapView.onDestroy()
  }

  private fun createRandomPoints(): Point {
    return Point.fromLngLat(
      random.nextDouble() * -360.0 + 180.0,
      random.nextDouble() * -180.0 + 90.0
    )
  }

  companion object {
    private const val ID_ICON_AIRPORT = "airport"
    private const val MAKI_ICON_CAR = "car-15"
    private const val MAKI_ICON_CAFE = "cafe-15"
    private const val MAKI_ICON_CIRCLE = "fire-station-15"
  }
}