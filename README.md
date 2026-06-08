# Parking App

台北市停車場地圖 App（Android）。

目前版本採用 OpenStreetMap + 台北市停車資料 + 自訂收費結構檔，提供地圖瀏覽、車種切換、空位篩選、收費顏色標示、搜尋與定位功能。

## 功能總覽

- 地圖顯示台北市停車場點位（OSMDroid / MAPNIK）。
- 依車種切換顯示參考費率：小客車、機車、大型重機、大客車。
- 空位篩選：只看有空位 / 不篩選。
- Marker 顏色分級（low / medium / high / unknown）與費率文字。
- 點擊停車場可看詳細資訊（收費說明、目前車種參考價、各類格位可用數）。
- 收費文字包含「半小時 / 上限」關鍵字時，會做重點提示與文字高亮。
- 關鍵字搜尋（先本地停車場名稱/地址，再 fallback Geocoder）。
- 目前位置定位與地圖跳轉。
- 側欄可手動更新遠端資料。
- 內建 AdMob Banner（debug 使用測試 ID）。

## 資料來源與載入邏輯

App 主要資料檔：

- TCMSV_alldesc.json
- TCMSV_allavailable.json
- payex_structured.json

啟動流程：

1. 啟動時會呼叫更新流程，先嘗試抓遠端資料。
2. 遠端下載成功後寫入 app internal storage（filesDir）。
3. 解析資料時，優先讀取 filesDir 中的檔案；若不存在則 fallback 到 assets。

遠端資料來源：

- 描述資料: https://tcgbusfs.blob.core.windows.net/blobtcmsv/TCMSV_alldesc.json
- 即時可用格位: https://tcgbusfs.blob.core.windows.net/blobtcmsv/TCMSV_allavailable.json
- 收費結構: https://raw.githubusercontent.com/YENCHUN-L/ParkAPI/refs/heads/main/payex_structured.json

更新節流：

- 15 分鐘內重複更新會被擋下並顯示提示。

## 座標處理規則

每筆停車場座標優先順序：

1. EntranceCoord.EntrancecoordInfo[0]（若值在台灣範圍）
2. tw97x/tw97y 轉 WGS84（若轉換後在台灣範圍）

台灣範圍檢查：

- 緯度 21.5..25.5
- 經度 119.0..122.5

不合法座標會跳過，不顯示在地圖上。

## 收費資料格式（目前實作）

目前程式只使用 payex_structured.json 的 vehicle_weekday_amount 結構。

範例：

```json
{
	"meta": {
		"hourly_price_level_rule": {
			"low_max": 40,
			"medium_max": 60
		}
	},
	"items": [
		{
			"id": "TPE0001",
			"payex": "收費文字...",
			"vehicle_weekday_amount": {
				"small_car": { "mon": 100, "tue": 100, "wed": 100, "thu": 100, "fri": 100, "sat": 100, "sun": 100 },
				"motorcycle": { "mon": null, "tue": null, "wed": null, "thu": null, "fri": null, "sat": null, "sun": null },
				"heavy_motorcycle": { "mon": null, "tue": null, "wed": null, "thu": null, "fri": null, "sat": null, "sun": null },
				"bus": { "mon": null, "tue": null, "wed": null, "thu": null, "fri": null, "sat": null, "sun": null }
			}
		}
	]
}
```

欄位說明：

- id: 停車場 ID（與停車場主資料 merge）。
- payex: 收費原文，用於詳情視窗顯示。
- vehicle_weekday_amount: 車種 x 星期費率表（數字或 null）。
- meta.hourly_price_level_rule: 顏色分級門檻（可選；若缺少則使用預設 lowMax=40、mediumMax=60）。

## 費率解析與顏色規則

當前車種 + 當天星期（mon..sun）對應到費率後：

- rate <= lowMax -> low（綠色）
- lowMax < rate <= mediumMax -> medium（黃色）
- rate > mediumMax -> high（紅色）
- 無資料 -> unknown（藍色）

Marker 會顯示費率文字（整數顯示整數，否則顯示 1 位小數）。

## UI 與互動

- 地圖拖曳/縮放後 120ms 防抖更新 marker。
- marker 尺寸會隨縮放層級調整（56..120）。
- 點擊 marker 會打開詳情對話框，並可一鍵開 Google 地圖導航。
- 詳情視窗會顯示：
	- 行政區、地址、電話、摘要、服務時間
	- 收費說明（payex）
	- 目前車種參考價格
	- 各類可用格位與資料更新時間

## 權限

- INTERNET
- ACCESS_NETWORK_STATE
- ACCESS_FINE_LOCATION
- ACCESS_COARSE_LOCATION
- WRITE_EXTERNAL_STORAGE（maxSdkVersion=28）

## 開發環境

- Android Gradle Plugin: 8.5.2
- Kotlin: 1.9.24
- compileSdk: 34
- minSdk: 26
- targetSdk: 34
- Java target: 17

## 本機建置（Windows PowerShell）

設定 SDK 環境變數後建置 debug APK：

```powershell
$env:ANDROID_HOME="C:/Users/YCL/AppData/Local/Android/Sdk"
$env:ANDROID_SDK_ROOT="C:/Users/YCL/AppData/Local/Android/Sdk"
.\tools\gradle-8.7\bin\gradle.bat --project-dir "." :app:assembleDebug
```

輸出：

- app/build/outputs/apk/debug/app-debug.apk

## Release AAB（目前可用流程）

本專案目前在某些環境下 :app:signReleaseBundle 會出現 AGP NPE，實務上採用以下流程：

1. 先產出 unsigned bundle（跳過 AGP sign task）
2. 再用 jarsigner 手動簽章為可上傳檔案

步驟：

```powershell
$env:ANDROID_HOME="C:/Users/YCL/AppData/Local/Android/Sdk"
$env:ANDROID_SDK_ROOT="C:/Users/YCL/AppData/Local/Android/Sdk"
.\tools\gradle-8.7\bin\gradle.bat --project-dir "." :app:bundleRelease -x :app:signReleaseBundle
```

unsigned bundle 位置：

- app/build/intermediates/intermediary_bundle/release/packageReleaseBundle/intermediary-bundle.aab

以 key.properties 指定的 keystore 簽章後輸出：

- app/build/outputs/bundle/release/app-release.aab

## 專案重點檔案

- App 主邏輯: app/src/main/java/com/example/parkingapp/MainActivity.kt
- Android 設定: app/src/main/AndroidManifest.xml
- App module 設定: app/build.gradle.kts
- 收費資料（產出）: Output/payex_structured.json
- 說明文件: APK_FEATURES.md

## 已知事項

- Geocoder.getFromLocationName 與 LocationManager.requestSingleUpdate 在新 API 標記為 deprecated，但目前仍可運作。
- 若要上架 Google Play，請確認 target API 符合當年度政策要求。

