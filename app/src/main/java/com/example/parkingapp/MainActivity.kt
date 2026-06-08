package com.example.parkingapp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

enum class VehicleType(val key: String, val label: String) {
    SMALL_CAR("small_car", "小客車"),
    MOTORCYCLE("motorcycle", "機車"),
    HEAVY_MOTORCYCLE("heavy_motorcycle", "大型重機"),
    BUS("bus", "大客車")
}

enum class VacancyFilter(val label: String) {
    AVAILABLE_ONLY("有空位"),
    ALL("不篩選")
}

data class PriceThresholds(
    val lowMax: Double = 40.0,
    val mediumMax: Double = 60.0
)

data class PayexProfile(
    val payex: String,
    val vehicleWeekdayAmount: Map<String, Map<String, Double?>>
)

data class MarkerPricingInfo(
    val hourlyRate: Double?,
    val level: String
)

data class ResolvedPricing(
    val rate: Double?,
    val unit: String?,
    val level: String
)

data class ParkingLot(
    val id: String,
    val name: String,
    val area: String,
    val address: String,
    val tel: String,
    val summary: String,
    val payex: String,
    val serviceTime: String,
    val totalCar: Int,
    val totalMotor: Int,
    val totalBus: Int,
    val availableCar: Int,
    val availableMotor: Int,
    val availableBus: Int,
    val availableHandicap: Int,
    val availablePregnancy: Int,
    val availableHeavyMotor: Int,
    val availableChargeSpots: Int?,
    val availabilityUpdateTime: String,
    val lat: Double,
    val lon: Double
)

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var btnMenu: ImageButton
    private lateinit var mapView: MapView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvMessage: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var rgVehicleType: RadioGroup
    private lateinit var rgVacancyFilter: RadioGroup
    private lateinit var fabLocation: FloatingActionButton
    private lateinit var adContainer: FrameLayout
    private var adViewBanner: AdView? = null
    private lateinit var tvSearchResult: TextView
    private lateinit var tvTermsAndLicense: TextView

    private var allParkingLots: List<ParkingLot> = emptyList()
    private var payexProfiles: Map<String, PayexProfile> = emptyMap()
    private var selectedVehicle: VehicleType = VehicleType.SMALL_CAR
    private var selectedVacancyFilter: VacancyFilter = VacancyFilter.AVAILABLE_ONLY
    private var priceThresholds = PriceThresholds()
    private val markerIconCache = HashMap<String, BitmapDrawable>()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var waitingForFirstFix = false

    private val remoteDescUrl = "https://tcgbusfs.blob.core.windows.net/blobtcmsv/TCMSV_alldesc.json"
    private val remoteAvailUrl = "https://tcgbusfs.blob.core.windows.net/blobtcmsv/TCMSV_allavailable.json"
    private val remotePayexUrl = "https://github.com/YENCHUN-L/Parking_App/blob/main/Output/payex_structured.json"
    private val remoteUpdateIntervalMs = 15 * 60 * 1000L
    private val prefsName = "parking_app_prefs"
    private val keyLastRemoteUpdateMs = "last_remote_update_ms"
    private var isRemoteUpdating = false
    private val adBannerTestUnitId = "ca-app-pub-3940256099942544/6300978111"

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { doUpdateMarkersInView() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) goToCurrentLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        btnMenu = findViewById(R.id.btnMenu)
        mapView = findViewById(R.id.mapView)
        progressBar = findViewById(R.id.progressBar)
        tvMessage = findViewById(R.id.tvMessage)
        tvStatus = findViewById(R.id.tvStatus)
        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        rgVehicleType = findViewById(R.id.rgVehicleType)
        rgVacancyFilter = findViewById(R.id.rgVacancyFilter)
        fabLocation = findViewById(R.id.fabLocation)
        adContainer = findViewById(R.id.adContainer)
        tvSearchResult = findViewById(R.id.tvSearchResult)
        tvTermsAndLicense = findViewById(R.id.tvTermsAndLicense)

        setupBannerAd()
        setupDrawer()
        setupMap()
        setupVehicleSwitcher()
        setupVacancyFilter()
        setupSearch()
        setupLocationButton()
        updateDataFromRemote(ignoreRateLimit = true, fallbackToLocalOnFailure = true)
    }

    private fun setupBannerAd() {
        MobileAds.initialize(this)
        val isDebugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val banner = AdView(this)
        banner.setAdSize(AdSize.BANNER)
        banner.adUnitId = if (isDebugBuild) {
            adBannerTestUnitId
        } else {
            getString(R.string.admob_banner_unit_id)
        }
        adContainer.removeAllViews()
        adContainer.addView(banner)
        adViewBanner = banner
        banner.loadAd(AdRequest.Builder().build())
    }

    private fun setupDrawer() {
        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                adContainer.visibility = if (slideOffset > 0f) View.GONE else View.VISIBLE
            }

            override fun onDrawerOpened(drawerView: View) {
                adContainer.visibility = View.GONE
            }

            override fun onDrawerClosed(drawerView: View) {
                adContainer.visibility = View.VISIBLE
            }
        })

        tvTermsAndLicense.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showTermsAndLicenseDialog()
        }

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_update_data -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    updateDataFromRemote()
                    true
                }

                else -> false
            }
        }
    }

    private fun showTermsAndLicenseDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.terms_and_license_title))
            .setMessage(getString(R.string.terms_and_license_content))
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun setupVehicleSwitcher() {
        selectedVehicle = VehicleType.SMALL_CAR
        rgVehicleType.check(R.id.rbSmallCar)
        rgVehicleType.setOnCheckedChangeListener { _, checkedId ->
            selectedVehicle = when (checkedId) {
                R.id.rbMotorcycle -> VehicleType.MOTORCYCLE
                R.id.rbHeavyMotorcycle -> VehicleType.HEAVY_MOTORCYCLE
                R.id.rbBus -> VehicleType.BUS
                else -> VehicleType.SMALL_CAR
            }
            doUpdateMarkersInView()
        }
    }

    private fun setupVacancyFilter() {
        selectedVacancyFilter = VacancyFilter.AVAILABLE_ONLY
        rgVacancyFilter.check(R.id.rbAvailableOnly)
        rgVacancyFilter.setOnCheckedChangeListener { _, checkedId ->
            selectedVacancyFilter = when (checkedId) {
                R.id.rbNoVacancyFilter -> VacancyFilter.ALL
                else -> VacancyFilter.AVAILABLE_ONLY
            }
            doUpdateMarkersInView()
        }
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(GeoPoint(25.05, 121.55))

        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleMarkerUpdate()
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleMarkerUpdate()
                return true
            }
        })
    }

    private fun scheduleMarkerUpdate() {
        updateHandler.removeCallbacks(updateRunnable)
        updateHandler.postDelayed(updateRunnable, 120)
    }

    private fun doUpdateMarkersInView() {
        if (allParkingLots.isEmpty()) return
        val bounds = try {
            mapView.boundingBox
        } catch (_: Exception) {
            return
        }

        mapView.overlays.clear()

        val visible = allParkingLots.filter { lot ->
            lot.lat in bounds.latSouth..bounds.latNorth &&
                lot.lon in bounds.lonWest..bounds.lonEast &&
                matchesCurrentVehicleFilter(lot)
        }

        val zoom = mapView.zoomLevelDouble
        for (lot in visible) {
            val pricing = resolveMarkerPricing(lot.id, selectedVehicle)
            val marker = Marker(mapView)
            marker.position = GeoPoint(lot.lat, lot.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.icon = createMarkerIcon(pricing, zoom)
            marker.title = lot.name
            marker.setOnMarkerClickListener { _, mv ->
                mv.controller.animateTo(marker.position)
                showParkingInfoDialog(lot)
                true
            }
            mapView.overlays.add(marker)
        }

        tvStatus.text = "車種: ${selectedVehicle.label} | 篩選: ${selectedVacancyFilter.label} | 可視: ${visible.size} / 共: ${allParkingLots.size}"
        mapView.invalidate()
    }

    private fun matchesCurrentVehicleFilter(lot: ParkingLot): Boolean {
        if (!supportsVehicleType(lot, selectedVehicle)) return false
        return when (selectedVacancyFilter) {
            VacancyFilter.AVAILABLE_ONLY -> hasVehicleVacancy(lot, selectedVehicle)
            VacancyFilter.ALL -> true
        }
    }

    private fun supportsVehicleType(lot: ParkingLot, vehicleType: VehicleType): Boolean {
        return when (vehicleType) {
            VehicleType.SMALL_CAR -> lot.availableCar >= 0
            VehicleType.MOTORCYCLE -> lot.availableMotor >= 0
            VehicleType.HEAVY_MOTORCYCLE -> lot.availableHeavyMotor >= 0
            VehicleType.BUS -> lot.availableBus >= 0
        }
    }

    private fun hasVehicleVacancy(lot: ParkingLot, vehicleType: VehicleType): Boolean {
        return when (vehicleType) {
            VehicleType.SMALL_CAR -> lot.availableCar > 0
            VehicleType.MOTORCYCLE -> lot.availableMotor > 0
            VehicleType.HEAVY_MOTORCYCLE -> lot.availableHeavyMotor > 0
            VehicleType.BUS -> lot.availableBus > 0
        }
    }

    private fun fitMapToAllMarkers(lots: List<ParkingLot>) {
        if (lots.isEmpty()) return
        val points = lots.map { GeoPoint(it.lat, it.lon) }
        val box = BoundingBox.fromGeoPoints(points)
        mapView.post {
            mapView.zoomToBoundingBox(box, true, 80)
            mapView.post {
                val z = mapView.zoomLevelDouble
                when {
                    z < 10.0 -> mapView.controller.setZoom(10.0)
                    z > 16.0 -> mapView.controller.setZoom(16.0)
                }
                doUpdateMarkersInView()
            }
        }
    }

    private fun createMarkerIcon(pricing: MarkerPricingInfo, zoomLevel: Double): BitmapDrawable {
        val rateLabel = formatHourlyRate(pricing.hourlyRate)
        val size = markerSizeForZoom(zoomLevel)
        val key = "${pricing.level}|$rateLabel|$size"
        markerIconCache[key]?.let { return it }

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = when (pricing.level) {
            "low" -> Color.parseColor("#2E7D32")
            "medium" -> Color.parseColor("#F9A825")
            "high" -> Color.parseColor("#C62828")
            "unknown" -> Color.parseColor("#1565C0")
            else -> Color.BLACK
        }
        canvas.drawCircle(size / 2f, size * 0.38f, size * 0.36f, paint)

        val path = Path()
        path.moveTo(size * 0.22f, size * 0.62f)
        path.lineTo(size * 0.78f, size * 0.62f)
        path.lineTo(size * 0.5f, size * 0.98f)
        path.close()
        canvas.drawPath(path, paint)

        paint.color = if (pricing.level == "medium") Color.BLACK else Color.WHITE
        paint.textSize = if (rateLabel.length <= 2) size * 0.28f else size * 0.22f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        canvas.drawText(rateLabel, size / 2f, size * 0.50f, paint)

        return BitmapDrawable(resources, bmp).also { markerIconCache[key] = it }
    }

    private fun markerSizeForZoom(zoomLevel: Double): Int {
        // Keep markers readable at high zoom and avoid oversized icons at low zoom.
        val scaled = 56.0 + (zoomLevel - 10.0) * 8.0
        return scaled.coerceIn(56.0, 120.0).toInt()
    }

    private fun StringBuilder.appendAvailabilityLine(label: String, value: Int) {
        if (value >= 0) {
            appendLine("$label：$value")
        }
    }

    private fun showParkingInfoDialog(lot: ParkingLot) {
        val currentPricing = resolveSelectedPricing(lot.id, selectedVehicle)
        val availabilityUpdateText = lot.availabilityUpdateTime.ifBlank { "未提供" }
        val payexText = lot.payex.ifBlank { "未提供" }
        val divider = "--------------------------------"

        // 1. 先定義關鍵字列表，方便後續檢查與標記
        val keywords = listOf("半小時", "上限")
        // 檢查收費說明中是否包含任一關鍵字
        val hasKeywords = keywords.any { payexText.contains(it) }

        // 2. 組合顯示文字
        val fullText = buildString {
            appendLine("[基本資訊]")
            appendLine("行政區：${lot.area}")
            appendLine("地址：${lot.address}")
            appendLine("電話：${lot.tel.ifBlank { "未提供" }}")
            appendLine("摘要：${lot.summary.ifBlank { "未提供" }}")
            if (lot.serviceTime.isNotEmpty()) appendLine("服務時間：${lot.serviceTime}")

            appendLine(divider)
            appendLine("收費說明：")
            appendLine(payexText)
            appendLine(divider)
            appendLine("[目前費率]")
            appendLine("目前車種：${selectedVehicle.label}")
            appendLine("參考價格：${formatPricingText(currentPricing)}")
            appendLine("*如參考價格有誤，詳見收費說明，實際價格以現場為主")

            // 只有當偵測到關鍵字時，才動態加入提示訊息
            if (hasKeywords) {
                appendLine("重點提示：本場包含「半小時」與「上限」收費機制，請詳閱說明。")
            }

            appendLine(divider)
            appendLine("[格位資訊]")
            appendAvailabilityLine("小客車可用", lot.availableCar)
            appendAvailabilityLine("機車可用", lot.availableMotor)
            appendAvailabilityLine("大客車可用", lot.availableBus)
            appendAvailabilityLine("身障車位可用", lot.availableHandicap)
            appendAvailabilityLine("孕婦車位可用", lot.availablePregnancy)
            appendAvailabilityLine("大型重機可用", lot.availableHeavyMotor)
            lot.availableChargeSpots?.let { appendLine("充電樁可用：$it") }
            appendLine("更新時間：$availabilityUpdateText")
        }

        // 3. 處理 SpannableString 進行標記
        val spannableMsg = SpannableString(fullText)

        // A. 標記紅色警告文字
        val warningText = "*如參考價格有誤，詳見收費說明，實際價格以現場為主"
        val warningIndex = fullText.indexOf(warningText)
        if (warningIndex != -1) {
            spannableMsg.setSpan(
                ForegroundColorSpan(Color.RED),
                warningIndex,
                warningIndex + warningText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // B. 標記「半小時」與「上限」 (包含原本的說明文字與我們動態加入的提示文字)
        for (keyword in keywords) {
            var index = fullText.indexOf(keyword)
            while (index != -1) {
                val end = index + keyword.length
                // 黃色背景
                spannableMsg.setSpan(
                    BackgroundColorSpan(Color.YELLOW),
                    index,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // 粗體效果
                spannableMsg.setSpan(
                    StyleSpan(Typeface.BOLD),
                    index,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                index = fullText.indexOf(keyword, end)
            }
        }

        // 4. 顯示 Dialog
        AlertDialog.Builder(this)
            .setTitle(lot.name)
            .setMessage(spannableMsg)
            .setNeutralButton("Google 地圖") { _, _ -> openGoogleNavigation(lot) }
            .setPositiveButton("關閉", null)
            .show()
    }


    private fun openGoogleNavigation(lot: ParkingLot) {
        val label = Uri.encode(lot.name)
        val mapUri = Uri.parse("geo:${lot.lat},${lot.lon}?q=${lot.lat},${lot.lon}($label)")
        val mapsIntent = Intent(Intent.ACTION_VIEW, mapUri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            startActivity(mapsIntent)
        } catch (_: ActivityNotFoundException) {
            // Fallback to browser map view when Google Maps app is unavailable.
            val fallbackUrl = "https://www.google.com/maps/search/?api=1&query=${lot.lat},${lot.lon}"
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
            startActivity(fallbackIntent)
        }
    }

    private fun loadData() {
        progressBar.visibility = View.VISIBLE
        tvMessage.visibility = View.GONE

        scope.launch {
            try {
                val lots = withContext(Dispatchers.IO) { parseData() }
                allParkingLots = lots
                progressBar.visibility = View.GONE
                if (lots.isEmpty()) {
                    tvMessage.text = "沒有可顯示的停車場資料"
                    tvMessage.visibility = View.VISIBLE
                } else {
                    fitMapToAllMarkers(lots)
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvMessage.text = "載入資料失敗：${e.message}"
                tvMessage.visibility = View.VISIBLE
            }
        }
    }

    private fun parseData(): List<ParkingLot> {
        val descJson = JSONObject(readRequiredDataText("TCMSV_alldesc.json"))
        val descParks = descJson.getJSONObject("data").getJSONArray("park")

        val availJson = JSONObject(readRequiredDataText("TCMSV_allavailable.json"))
        val availData = availJson.getJSONObject("data")
        val availParks = availData.getJSONArray("park")
        val availabilityUpdateTime = availData.optString("UPDATETIME", "")

        val payexData = parsePayexStructuredData()
        val payexOverrideMap = payexData.first
        payexProfiles = payexData.second

        val availMap = HashMap<String, JSONObject>()
        for (i in 0 until availParks.length()) {
            val p = availParks.getJSONObject(i)
            val availId = normalizeParkingId(p.optString("id", ""))
            if (availId.isNotEmpty()) {
                availMap[availId] = p
            }
        }

        val result = mutableListOf<ParkingLot>()

        for (i in 0 until descParks.length()) {
            val p = descParks.getJSONObject(i)
            val id = normalizeParkingId(p.optString("id", ""))
            if (id.isEmpty()) continue

            var lat = Double.NaN
            var lon = Double.NaN

            val entranceCoord = p.optJSONObject("EntranceCoord")
            if (entranceCoord != null) {
                val infoArray = entranceCoord.optJSONArray("EntrancecoordInfo")
                if (infoArray != null && infoArray.length() > 0) {
                    val info = infoArray.getJSONObject(0)
                    val xcod = info.optString("Xcod", "").toDoubleOrNull()
                    val ycod = info.optString("Ycod", "").toDoubleOrNull()
                    if (xcod != null && ycod != null && isInTaiwan(xcod, ycod)) {
                        lat = xcod
                        lon = ycod
                    }
                }
            }

            if (lat.isNaN()) {
                val tw97x = p.optString("tw97x", "").toDoubleOrNull()
                val tw97y = p.optString("tw97y", "").toDoubleOrNull()
                if (tw97x != null && tw97y != null) {
                    val (clat, clon) = twd97ToWgs84(tw97x, tw97y)
                    if (isInTaiwan(clat, clon)) {
                        lat = clat
                        lon = clon
                    }
                }
            }

            if (lat.isNaN() || lon.isNaN()) continue

            val avail = availMap[id]
            val availCar = avail?.optInt("availablecar", -9) ?: -9
            val availMotor = avail?.optInt("availablemotor", -9) ?: -9
            val availBus = avail?.optInt("availablebus", -9) ?: -9
            val availHandicap = avail?.optInt("availablehandicap", -9) ?: -9
            val availPregnancy = avail?.optInt("availablepregnancy", -9) ?: -9
            val availHeavyMotor = avail?.optInt("availableheavymotor", -9) ?: -9
            val availableChargeSpots = avail
                ?.optJSONObject("ChargeStation")
                ?.optJSONArray("scoketStatusList")
                ?.let { statusList ->
                    var count = 0
                    for (j in 0 until statusList.length()) {
                        val socket = statusList.optJSONObject(j) ?: continue
                        if (socket.optString("spot_status", "").trim() == "待機中") {
                            count += 1
                        }
                    }
                    count
                }

            result.add(
                ParkingLot(
                    id = id,
                    name = p.optString("name", ""),
                    area = p.optString("area", ""),
                    address = p.optString("address", ""),
                    tel = p.optString("tel", ""),
                    summary = p.optString("summary", ""),
                    payex = payexOverrideMap[id] ?: p.optString("payex", ""),
                    serviceTime = p.optString("serviceTime", ""),
                    totalCar = p.optInt("totalcar", 0),
                    totalMotor = p.optInt("totalmotor", 0),
                    totalBus = p.optInt("totalbus", 0),
                    availableCar = availCar,
                    availableMotor = availMotor,
                    availableBus = availBus,
                    availableHandicap = availHandicap,
                    availablePregnancy = availPregnancy,
                    availableHeavyMotor = availHeavyMotor,
                    availableChargeSpots = availableChargeSpots,
                    availabilityUpdateTime = availabilityUpdateTime,
                    lat = lat,
                    lon = lon
                )
            )
        }

        return result
    }

    private fun readRequiredDataText(fileName: String): String {
        val localFile = File(filesDir, fileName)
        if (localFile.exists()) {
            return localFile.readText(Charsets.UTF_8)
        }
        return assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun readOptionalDataText(fileName: String): String? {
        val localFile = File(filesDir, fileName)
        if (localFile.exists()) {
            return localFile.readText(Charsets.UTF_8)
        }
        return try {
            assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePayexStructuredData(): Pair<Map<String, String>, Map<String, PayexProfile>> {
        val localRaw = runCatching {
            val localFile = File(filesDir, "payex_structured.json")
            if (localFile.exists()) localFile.readText(Charsets.UTF_8) else null
        }.getOrNull()

        val assetRaw = runCatching {
            assets.open("payex_structured.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()

        val candidates = linkedSetOf<String>().apply {
            if (!localRaw.isNullOrBlank()) add(localRaw)
            if (!assetRaw.isNullOrBlank()) add(assetRaw)
        }

        for (raw in candidates) {
            val parsed = runCatching {
                val root = JSONObject(raw)
            val threshold = root.optJSONObject("meta")
                ?.optJSONObject("hourly_price_level_rule")
            if (threshold != null) {
                priceThresholds = PriceThresholds(
                    lowMax = threshold.optDouble("low_max", threshold.optDouble("low_cutoff", 40.0)),
                    mediumMax = threshold.optDouble("medium_max", threshold.optDouble("high_cutoff", 60.0))
                )
            }

                val items = root.optJSONArray("items") ?: return@runCatching null

            val payexMap = mutableMapOf<String, String>()
            val profileMap = mutableMapOf<String, PayexProfile>()

            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val id = normalizeParkingId(obj.optString("id", ""))
                if (id.isEmpty()) continue

                val payex = obj.optString("payex", "")
                if (payex.isNotEmpty()) {
                    payexMap[id] = payex
                }

                val vehicleWeekdayAmount = parseVehicleWeekdayAmount(obj.optJSONObject("vehicle_weekday_amount"))
                profileMap[id] = PayexProfile(
                    payex = payex,
                    vehicleWeekdayAmount = vehicleWeekdayAmount
                )
            }

                Pair(payexMap, profileMap)
            }.getOrNull()

            if (parsed != null) {
                return parsed
            }
        }

        return Pair(emptyMap(), emptyMap())
    }

    private fun parseVehicleWeekdayAmount(weekdayObj: JSONObject?): Map<String, Map<String, Double?>> {
        if (weekdayObj == null) return emptyMap()
        val result = mutableMapOf<String, MutableMap<String, Double?>>()
        val vehicleIterator = weekdayObj.keys()
        while (vehicleIterator.hasNext()) {
            val vehicleKey = vehicleIterator.next()
            val weekdays = weekdayObj.optJSONObject(vehicleKey) ?: continue
            val dayMap = mutableMapOf<String, Double?>()
            val dayIterator = weekdays.keys()
            while (dayIterator.hasNext()) {
                val dayKey = dayIterator.next().lowercase(Locale.ROOT)
                dayMap[dayKey] = if (weekdays.isNull(dayKey)) {
                    null
                } else {
                    weekdays.optDouble(dayKey)
                }
            }
            result[vehicleKey] = dayMap
        }
        return result
    }

    private fun resolveMarkerPricing(id: String, vehicleType: VehicleType): MarkerPricingInfo {
        val pricing = resolveSelectedPricing(id, vehicleType)
        return MarkerPricingInfo(
            hourlyRate = pricing.rate,
            level = pricing.level
        )
    }

    private fun resolveSelectedPricing(id: String, vehicleType: VehicleType): ResolvedPricing {
        val profile = payexProfiles[normalizeParkingId(id)] ?: return ResolvedPricing(null, null, "unknown")
        val now = LocalDateTime.now()
        val weekday = weekdayKey(now.dayOfWeek)

        val weekdayRate = profile.vehicleWeekdayAmount[vehicleType.key]
            ?.get(weekday)
        if (weekdayRate != null) {
            return ResolvedPricing(
                rate = weekdayRate,
                unit = "hour",
                level = toPriceLevel(weekdayRate)
            )
        }

        return ResolvedPricing(null, null, "unknown")
    }

    private fun formatPricingText(pricing: ResolvedPricing): String {
        if (pricing.rate == null || pricing.unit == null) return "未提供"
        return "${formatHourlyRate(pricing.rate)}元/${pricingUnitLabel(pricing.unit)}"
    }

    private fun pricingUnitLabel(unit: String): String {
        return when (unit) {
            "hour" -> "時"
            "entry" -> "次"
            "month" -> "月"
            else -> unit
        }
    }

    private fun formatHourlyRate(rate: Double?): String {
        if (rate == null) return "--"
        val intRate = rate.toInt()
        return if (kotlin.math.abs(rate - intRate.toDouble()) < 0.001) {
            intRate.toString()
        } else {
            String.format(Locale.US, "%.1f", rate)
        }
    }

    private fun toPriceLevel(rate: Double): String {
        return when {
            rate <= priceThresholds.lowMax -> "low"
            rate <= priceThresholds.mediumMax -> "medium"
            else -> "high"
        }
    }

    private fun normalizeParkingId(rawId: String): String {
        return rawId.trim().uppercase(Locale.ROOT)
    }

    private fun weekdayKey(dayOfWeek: DayOfWeek): String {
        return when (dayOfWeek) {
            DayOfWeek.MONDAY -> "mon"
            DayOfWeek.TUESDAY -> "tue"
            DayOfWeek.WEDNESDAY -> "wed"
            DayOfWeek.THURSDAY -> "thu"
            DayOfWeek.FRIDAY -> "fri"
            DayOfWeek.SATURDAY -> "sat"
            DayOfWeek.SUNDAY -> "sun"
        }
    }

    private fun updateDataFromRemote(
        ignoreRateLimit: Boolean = false,
        fallbackToLocalOnFailure: Boolean = false
    ) {
        if (isRemoteUpdating) {
            showSearchResult("更新資料進行中，請稍候")
            return
        }

        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val lastUpdateMs = prefs.getLong(keyLastRemoteUpdateMs, 0L)
        val elapsed = now - lastUpdateMs
        if (!ignoreRateLimit && lastUpdateMs > 0L && elapsed < remoteUpdateIntervalMs) {
            val remainingMs = remoteUpdateIntervalMs - elapsed
            val remainingMinutes = (remainingMs + 59999L) / 60000L
            showSearchResult("更新過於頻繁，請約 ${remainingMinutes} 分鐘後再試")
            return
        }

        isRemoteUpdating = true
        prefs.edit().putLong(keyLastRemoteUpdateMs, now).apply()
        progressBar.visibility = View.VISIBLE
        tvMessage.visibility = View.GONE
        showSearchResult("更新資料中...")

        scope.launch {
            try {
                val warning = withContext(Dispatchers.IO) { downloadRemoteDataFiles() }
                markerIconCache.clear()

                val lots = withContext(Dispatchers.IO) { parseData() }
                allParkingLots = lots

                progressBar.visibility = View.GONE
                if (lots.isEmpty()) {
                    tvMessage.text = "沒有可顯示的停車場資料"
                    tvMessage.visibility = View.VISIBLE
                } else {
                    tvMessage.visibility = View.GONE
                    doUpdateMarkersInView()
                }
                if (warning == null) {
                    showSearchResult("更新資料完成")
                } else {
                    showSearchResult("更新完成（收費沿用既有資料）")
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                showSearchResult("更新失敗：${e.message ?: "未知錯誤"}")
                if (fallbackToLocalOnFailure && allParkingLots.isEmpty()) {
                    loadData()
                }
            } finally {
                isRemoteUpdating = false
            }
        }
    }

    private fun downloadRemoteDataFiles(): String? {
        downloadToInternalFile(remoteDescUrl, "TCMSV_alldesc.json", validateJson = true, requiredTopLevelKey = "data")
        downloadToInternalFile(remoteAvailUrl, "TCMSV_allavailable.json", validateJson = true, requiredTopLevelKey = "data")

        return try {
            downloadPayexStructuredFromGithub()
            null
        } catch (_: Exception) {
            "payex_structured download failed"
        }
    }

    private fun downloadPayexStructuredFromGithub() {
        downloadToInternalFile(
            toRawGithubContentUrl(remotePayexUrl),
            "payex_structured.json",
            validateJson = true,
            requiredTopLevelKey = "items"
        )
    }

    private fun downloadToInternalFile(
        urlString: String,
        fileName: String,
        validateJson: Boolean = false,
        requiredTopLevelKey: String? = null
    ) {
        val targetFile = File(filesDir, fileName)
        val tempFile = File(filesDir, "$fileName.tmp")

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code for $fileName")
            }

            conn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!tempFile.exists() || tempFile.length() < 10L) {
                throw IOException("下載內容異常：$fileName")
            }

            if (validateJson) {
                val content = tempFile.readText(Charsets.UTF_8)
                if (!isLikelyJson(content)) {
                    throw IOException("下載內容非 JSON：$fileName")
                }
                if (requiredTopLevelKey != null) {
                    val json = JSONObject(content)
                    if (!json.has(requiredTopLevelKey)) {
                        throw IOException("JSON 欄位不符：$fileName")
                    }
                }
            }

            tempFile.copyTo(targetFile, overwrite = true)
        } finally {
            conn.disconnect()
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun isLikelyJson(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private fun toRawGithubContentUrl(url: String): String {
        val blobMarker = "/blob/"
        if (!url.contains("github.com") || !url.contains(blobMarker)) return url

        val hostAdjusted = url.replace("https://github.com/", "https://raw.githubusercontent.com/")
        return hostAdjusted.replace(blobMarker, "/")
    }

    private fun isInTaiwan(lat: Double, lon: Double): Boolean =
        lat in 21.5..25.5 && lon in 119.0..122.5

    private fun twd97ToWgs84(easting: Double, northing: Double): Pair<Double, Double> {
        val a = 6378137.0
        val f = 1.0 / 298.257222101
        val b = a * (1.0 - f)
        val e2 = 1.0 - (b * b) / (a * a)
        val k0 = 0.9999
        val lon0 = Math.toRadians(121.0)

        val e = easting - 250000.0
        val n = northing

        val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))
        val m = n / k0
        val mu = m / (a * (1.0 - e2 / 4.0 - 3.0 * e2.pow(2) / 64.0 - 5.0 * e2.pow(3) / 256.0))

        val phi1 = mu +
            (3.0 * e1 / 2.0 - 27.0 * e1.pow(3) / 32.0) * Math.sin(2.0 * mu) +
            (21.0 * e1.pow(2) / 16.0 - 55.0 * e1.pow(4) / 32.0) * Math.sin(4.0 * mu) +
            (151.0 * e1.pow(3) / 96.0) * Math.sin(6.0 * mu) +
            (1097.0 * e1.pow(4) / 512.0) * Math.sin(8.0 * mu)

        val sinPhi1 = Math.sin(phi1)
        val cosPhi1 = Math.cos(phi1)
        val tanPhi1 = Math.tan(phi1)
        val n1 = a / sqrt(1.0 - e2 * sinPhi1 * sinPhi1)
        val t1 = tanPhi1 * tanPhi1
        val c1 = (e2 / (1.0 - e2)) * cosPhi1 * cosPhi1
        val r1 = a * (1.0 - e2) / (1.0 - e2 * sinPhi1 * sinPhi1).pow(1.5)
        val d = e / (n1 * k0)

        val lat = phi1 - (n1 * tanPhi1 / r1) * (
            d * d / 2.0 -
                (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * e2 / (1.0 - e2)) * d.pow(4) / 24.0 +
                (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * e2 / (1.0 - e2) - 3.0 * c1 * c1) * d.pow(6) / 720.0
            )

        val lon = lon0 + (
            d -
                (1.0 + 2.0 * t1 + c1) * d.pow(3) / 6.0 +
                (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * e2 / (1.0 - e2) + 24.0 * t1 * t1) * d.pow(5) / 120.0
            ) / cosPhi1

        return Pair(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    private fun setupSearch() {
        btnSearch.setOnClickListener {
            val query = etSearch.text.toString().trim()
            if (query.isNotEmpty()) performSearch(query)
        }
        etSearch.setOnEditorActionListener { _, _, _ ->
            val query = etSearch.text.toString().trim()
            if (query.isNotEmpty()) performSearch(query)
            true
        }
    }

    private fun performSearch(query: String) {
        val local = allParkingLots.firstOrNull { lot ->
            matchesCurrentVehicleFilter(lot) &&
                (
                    lot.name.contains(query, ignoreCase = true) ||
                        lot.address.contains(query, ignoreCase = true)
                    )
        }
        if (local != null) {
            val point = GeoPoint(local.lat, local.lon)
            mapView.controller.animateTo(point)
            if (mapView.zoomLevelDouble < 16.0) mapView.controller.setZoom(16.0)
            showSearchResult("找到：${local.name}")
            return
        }

        scope.launch {
            try {
                val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(query, 1)
                }
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val point = GeoPoint(addr.latitude, addr.longitude)
                    mapView.controller.animateTo(point)
                    if (mapView.zoomLevelDouble < 16.0) mapView.controller.setZoom(16.0)
                    showSearchResult("地址找到：${addr.getAddressLine(0)}")
                } else {
                    showSearchResult("找不到「$query」")
                }
            } catch (_: Exception) {
                showSearchResult("找不到「$query」")
            }
        }
    }

    private fun showSearchResult(msg: String) {
        tvSearchResult.text = msg
        tvSearchResult.visibility = View.VISIBLE
        tvSearchResult.removeCallbacks(null)
        tvSearchResult.postDelayed({ tvSearchResult.visibility = View.GONE }, 3000)
    }

    private fun setupLocationButton() {
        fabLocation.setOnClickListener {
            if (hasLocationPermission()) {
                goToCurrentLocation()
            } else {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun goToCurrentLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        val lastKnown = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()

        if (lastKnown != null) {
            moveToLocation(lastKnown)
            return
        }

        waitingForFirstFix = true
        showSearchResult("正在取得位置...")
        try {
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (waitingForFirstFix) {
                            waitingForFirstFix = false
                            runOnUiThread { moveToLocation(location) }
                        }
                    }
                }, Looper.getMainLooper())
            } else {
                showSearchResult("無法取得位置")
            }
        } catch (_: SecurityException) {
            showSearchResult("無位置權限")
        }
    }

    private fun moveToLocation(location: Location) {
        val point = GeoPoint(location.latitude, location.longitude)
        mapView.controller.animateTo(point)
        if (mapView.zoomLevelDouble < 16.0) mapView.controller.setZoom(16.0)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        adViewBanner?.resume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        adViewBanner?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        updateHandler.removeCallbacks(updateRunnable)
        adViewBanner?.destroy()
        adViewBanner = null
    }
}
