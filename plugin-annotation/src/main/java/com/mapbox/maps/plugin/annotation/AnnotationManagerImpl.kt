package com.mapbox.maps.plugin.annotation

import android.graphics.PointF
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.common.Logger
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Geometry
import com.mapbox.geojson.Point
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.StyleManagerInterface
import com.mapbox.maps.extension.style.expressions.dsl.generated.literal
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.Layer
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSource
import com.mapbox.maps.plugin.InvalidPluginConfigurationException
import com.mapbox.maps.plugin.PLUGIN_GESTURE_CLASS_NAME
import com.mapbox.maps.plugin.delegates.MapDelegateProvider
import com.mapbox.maps.plugin.delegates.MapFeatureQueryDelegate
import com.mapbox.maps.plugin.delegates.MapProjectionDelegate
import com.mapbox.maps.plugin.delegates.MapStyleStateDelegate
import com.mapbox.maps.plugin.gestures.GesturesPlugin
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.OnMoveListener
import java.util.*

/**
 * Base class for annotation managers
 */
abstract class AnnotationManagerImpl<G : Geometry, T : Annotation<G>, S : AnnotationOptions<G, T>, D : OnAnnotationDragListener<T>, U : OnAnnotationClickListener<T>, V : OnAnnotationLongClickListener<T>, L : Layer>(
  /** The delegateProvider */
  final override val delegateProvider: MapDelegateProvider,
  private val belowLayerId: String?,
  private val touchAreaShiftX: Int,
  private val touchAreaShiftY: Int
) : AnnotationManager<G, T, S, D, U, V> {
  protected lateinit var style: StyleManagerInterface
  private var mapProjectionDelegate: MapProjectionDelegate = delegateProvider.mapProjectionDelegate
  private var mapFeatureQueryDelegate: MapFeatureQueryDelegate =
    delegateProvider.mapFeatureQueryDelegate
  private var styleStateDelegate: MapStyleStateDelegate = delegateProvider.styleStateDelegate
  internal lateinit var layer: L
  protected lateinit var source: GeoJsonSource
  protected val dataDrivenPropertyUsageMap: MutableMap<String, Boolean> = HashMap()

  private var currentId = 0L
  private var width = 0
  private var height = 0
  private val mapClickResolver = MapClick()
  private val mapLongClickResolver = MapLongClick()
  private val mapMoveResolver = MapMove()
  private var draggedAnnotation: T? = null

  private var gesturesPlugin: GesturesPlugin = delegateProvider.mapPluginProviderDelegate.getPlugin(
    Class.forName(
      PLUGIN_GESTURE_CLASS_NAME
    ) as Class<GesturesPlugin>
  )
    ?: throw InvalidPluginConfigurationException(
      "Can't look up an instance of plugin, " +
        "is it available on the clazz path and loaded through the map?"
    )

  /**
   * The added annotations
   */
  override val annotations = mutableMapOf<Long, T>()

  /**
   * The added dragListensers
   */
  override val dragListeners = mutableListOf<D>()

  /**
   * The Added clickListeners
   */
  override val clickListeners = mutableListOf<U>()

  /**
   * The added longClickListeners
   */
  override val longClickListeners = mutableListOf<V>()

  init {
    gesturesPlugin.addOnMapClickListener(mapClickResolver)
    gesturesPlugin.addOnMapLongClickListener(mapLongClickResolver)
    gesturesPlugin.addOnMoveListener(mapMoveResolver)
  }

  /**
   * Get the key of the id of the annotation.
   *
   * @return the key of the id of the annotation
   */
  abstract fun getAnnotationIdKey(): String

  /**
   * Create the source for managed annotations
   *
   * @return the GeoJsonSource created
   */
  abstract fun createSource(): GeoJsonSource

  /**
   * Create the layer for managed annotations
   *
   * @return the layer created
   */
  abstract fun createLayer(): L

  /**
   * Set filter on the managed annotations.
   */
  abstract var layerFilter: Expression?

  protected fun initLayerAndSource() {
    initializeDataDrivenPropertyMap()
    source = createSource()
    layer = createLayer()
    if (belowLayerId == null) {
      style.addLayer(layer)
    } else {
      style.addLayerBelow(layer, belowLayerId)
    }
    updateSource()
  }

  /**
   * Create an annotation with the option
   */
  override fun create(option: S): T {
    return option.build(currentId, this).also {
      annotations.put(it.id, it)
      currentId++
      updateSource()
    }
  }

  /**
   * Create some annotations with the options
   */
  override fun create(options: List<S>): List<T> {
    val list = options.map { option ->
      option.build(currentId, this).also {
        annotations.put(it.id, it)
        currentId++
      }
    }
    updateSource()
    return list
  }

  /**
   * Delete the annotation
   */
  override fun delete(annotation: T) {
    annotations.remove(annotation.id)
    updateSource()
  }

  /**
   * Delete annotations in the list
   */
  override fun delete(annotations: List<T>) {
    annotations.forEach {
      this.annotations.remove(it.id)
    }
    updateSource()
  }

  /**
   * Delete all the added annotations
   */
  override fun deleteAll() {
    annotations.clear()
    updateSource()
  }

  /**
   * Trigger an update to the underlying source
   */
  private fun updateSource() {
    val features = annotations.map {
      val annotation = Feature.fromGeometry(it.value.geometry, it.value.jsonObject)
      it.value.setUsedDataDrivenProperties()
      annotation
    }
    source.featureCollection(FeatureCollection.fromFeatures(features))
    if (style.getSource(source.sourceId) == null) {
      style.addSource(source)
    }
  }

  /**
   * Update the annotation
   */
  override fun update(annotation: T) {
    if (annotations.containsKey(annotation.id)) {
      annotations[annotation.id] = annotation
      updateSource()
    } else {
      Logger.e(
        TAG,
        "Can't update annotation: $annotation.toString(), the annotation isn't an active annotation."
      )
    }
  }

  /**
   * Update annotations in the list
   */
  override fun update(annotations: List<T>) {
    annotations.forEach {
      if (this.annotations.containsValue(it)) {
        this.annotations[it.id] = it
      } else {
        Logger.e(
          TAG,
          "Can't update annotation: $it.toString(), the annotation isn't an active annotation."
        )
      }
    }
    updateSource()
  }

  /**
   * Invoked when Mapview is destroyed
   */
  override fun onDestroy() {
    gesturesPlugin.removeOnMapClickListener(mapClickResolver)
    gesturesPlugin.removeOnMapLongClickListener(mapLongClickResolver)
    gesturesPlugin.removeOnMoveListener(mapMoveResolver)
    dragListeners.clear()
    clickListeners.clear()
    longClickListeners.clear()
  }

  /**
   * Class handle the map click event
   */
  inner class MapClick : OnMapClickListener {
    /**
     * Called when the user clicks on the map view.
     *
     * @param point The projected map coordinate the user clicked on.
     * @return True if this click should be consumed and not passed further to other listeners registered afterwards,
     * false otherwise.
     */
    override fun onMapClick(point: Point): Boolean {
      if (clickListeners.isEmpty()) {
        return false
      }
      queryMapForFeatures(point) {
        it?.let {
          clickListeners.forEach { listener ->
            listener.onAnnotationClick(it)
          }
        }
      }
      return false
    }
  }

  /**
   * Class handle the map long click event
   */
  inner class MapLongClick : OnMapLongClickListener {
    /**
     * Called when the user long clicks on the map view.
     *
     * @param point The projected map coordinate the user clicked on.
     * @return True if this click should be consumed and not passed further to other listeners registered afterwards,
     * false otherwise.
     */
    override fun onMapLongClick(point: Point): Boolean {
      if (longClickListeners.isEmpty()) {
        return false
      }
      queryMapForFeatures(point) {
        it?.let {
          longClickListeners.forEach { listener ->
            listener.onAnnotationLongClick(it)
          }
        }
      }
      return false
    }
  }

  /**
   * Class handle the map move event
   */
  inner class MapMove : OnMoveListener {
    /**
     * Called when the move gesture is starting.
     */
    override fun onMoveBegin(detector: MoveGestureDetector) {
      if (detector.pointersCount == 1) {
        queryMapForFeatures(
          ScreenCoordinate(
            detector.focalPoint.x.toDouble(),
            detector.focalPoint.y.toDouble()
          )
        ) {
          it?.let {
            startDragging(it)
          }
        }
      }
    }

    /**
     * Called when the move gesture is executing.
     */
    override fun onMove(detector: MoveGestureDetector): Boolean {
      if (draggedAnnotation != null && (detector.pointersCount > 1 || !draggedAnnotation!!.isDraggable)) {
        // Stopping the drag when we don't work with a simple, on-pointer move anymore
        stopDragging(draggedAnnotation)
        return true
      }

      // Updating symbol's position
      draggedAnnotation?.let { annotation ->
        val moveObject = detector.getMoveObject(0)
        val x = moveObject.currentX - touchAreaShiftX
        val y = moveObject.currentY - touchAreaShiftY
        val pointF = PointF(x, y)
        if (pointF.x < 0 || pointF.y < 0 || pointF.x > width || pointF.y > height) {
          stopDragging(draggedAnnotation)
          return true
        }
        val shiftedGeometry: G? = delegateProvider.let {
          annotation.getOffsetGeometry(
            it.mapProjectionDelegate, moveObject, touchAreaShiftX, touchAreaShiftY
          )
        }
        shiftedGeometry?.let { geometry ->
          annotation.geometry = geometry
          updateSource()
          dragListeners.forEach {
            it.onAnnotationDrag(annotation)
          }
          return true
        }
      }
      return false
    }

    /**
     * Called when the move gesture is ending.
     */
    override fun onMoveEnd(detector: MoveGestureDetector) {
      // Stopping the drag when move ends
      stopDragging(draggedAnnotation)
    }

    private fun startDragging(annotation: T): Boolean {
      if (annotation.isDraggable) {
        dragListeners.forEach { it.onAnnotationDragStarted(annotation) }
        draggedAnnotation = annotation
        return true
      }
      return false
    }

    private fun stopDragging(annotation: T?) {
      if (annotation != null) {
        dragListeners.forEach { it.onAnnotationDragFinished(annotation) }
      }
      draggedAnnotation = null
    }
  }

  /**
   * Invoked when MapView's width and height have changed.
   * @param width the width of mapView
   * @param height the height of mapView
   */
  override fun onSizeChanged(width: Int, height: Int) {
    this.width = width
    this.height = height
  }

  /**
   * Enable a data-driven property
   */
  override fun enableDataDrivenProperty(property: String) {
    if (dataDrivenPropertyUsageMap[property] == false) {
      dataDrivenPropertyUsageMap[property] = true
      setDataDrivenPropertyIsUsed(property)
    }
  }

  /**
   * Update a data-driven property to used state. Please visit https://docs.mapbox.com/android/maps/guides/data-driven-styling/ for more details about data-driven-styling
   */
  protected abstract fun setDataDrivenPropertyIsUsed(property: String)

  /**
   * Init data-driven properties map. please visit https://docs.mapbox.com/android/maps/guides/data-driven-styling/ for more details about data-driven-styling
   */
  protected abstract fun initializeDataDrivenPropertyMap()

  /**
   * Query the rendered annotation around the point
   *
   * @param point the point for querying
   * @param callback query callback
   */
  fun queryMapForFeatures(point: Point, callback: QueryAnnotationCallback<T>) {
    val screenCoordinate = mapProjectionDelegate.pixelForCoordinate(point)
    queryMapForFeatures(screenCoordinate, callback)
  }

  /**
   * Query the rendered annotation around the point
   *
   * @param screenCoordinate the screenCoordinate for querying
   * @param callback query callback
   */
  fun queryMapForFeatures(
    screenCoordinate: ScreenCoordinate,
    callback: QueryAnnotationCallback<T>
  ) {
    mapFeatureQueryDelegate.queryRenderedFeatures(
      screenCoordinate,
      RenderedQueryOptions(
        listOf(
          layer.layerId
        ),
        literal(true)
      )
    ) { features ->
      features.value?.let { featureList ->
        if (featureList.isNotEmpty()) {
          val id = featureList.first().getProperty(getAnnotationIdKey()).asLong
          callback.onQueryAnnotation(annotations[id])
        }
      }
    }
  }

  /**
   * Static variables and methods.
   */
  companion object {
    /**
     * Tag for log
     */
    private const val TAG = "AnnotationManagerImpl"
  }
}