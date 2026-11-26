# 繁中逐字稿（Android）

繁體中文語音轉逐字稿 App，有兩種模式：

| 功能 | 做法 | 需要 |
|---|---|---|
| **即時轉寫** | 手機內建 Google 語音辨識（zh-TW），說話時一邊顯示文字，每句結束後自動接續下一句，可以長時間連續辨識；畫面會保持亮著 | 不需要 API Key；可選擇離線辨識 |
| **檔案批次轉寫** | 一次選多個音檔或影片，App 先在手機上轉成 16kHz WAV 並自動切段，再送到 Whisper 辨識 | Groq 或 OpenAI 的 API Key |

其他功能：
- 支援 mp3 / m4a / aac / wav / ogg / flac / mp4 等格式（Android 能解碼的都可以）
- 可以從錄音機、LINE、檔案管理員用「分享」把音檔直接送進 App
- 每句附時間戳記；可複製、分享、存成 TXT，或批次檔案存成 **SRT 字幕**
- 即時轉寫會依每次按下「開始」的實際時間保存紀錄，可匯出全部紀錄或重置清除
- 「全部匯出」：選一個資料夾，把所有結果一次存成 TXT 和 SRT
- 遇到 429 或網路錯誤會自動重試；失敗的檔案可以單獨重試

## 取得 APK

### 方法 A：GitHub 自動編譯（不必安裝任何工具）
1. 在 GitHub 新增一個 repository，把這個資料夾裡的所有檔案上傳（記得包含 `.github` 資料夾）
2. 到 **Actions** 分頁，等「Build APK」跑完（約 5 分鐘）
3. 在執行結果頁面下方的 **Artifacts** 下載 `ZhTranscriber-apk`，解壓縮後就是 `app-release.apk`
4. 把 APK 傳到手機上安裝（需要允許「安裝不明來源的應用程式」）

### 方法 B：Android Studio
用 Android Studio 開啟這個資料夾 → 等 Gradle 同步完成 → 選 **Build ▸ Build APK(s)**，或直接接上手機按 Run。

> 環境需求：JDK 17、compileSdk 35；App 支援 Android 8.0（API 26）以上。

## 第一次使用
1. **設定** 分頁 → 按「套用 Groq」→ 貼上 API Key（到 https://console.groq.com 免費申請）
2. **即時轉寫** 分頁 → 按「開始」→ 允許麥克風權限 → 開始說話
3. **檔案批次** 分頁 → 按「＋ 選擇音檔」→ 按「開始轉寫」

## 小提示
- **即時轉寫出現簡體字或辨識不出來**：到手機的「Google」App → 設定 → 語音 → 語言，選「中文（台灣）」；如果要離線使用，同一個地方可以下載離線語音辨識包。
- **想提高批次辨識的準確度**：在「提示詞」欄位加上人名、專有名詞，例如「與會者：王小明、陳大文；主題：ESP32、L298N」。
- **費用參考**：Groq 的 whisper-large-v3 有免費額度；OpenAI 的 whisper-1 約每分鐘 US$0.006。
- 也可以把 API 網址改成自己架設、相容 OpenAI 格式的 faster-whisper 伺服器，例如 `http://192.168.x.x:8000/v1`。
- 有些手機在每句之間仍會發出提示音，這是手機的語音服務造成的，跟這個 App 無關。

## 程式結構
```
app/src/main/java/tw/transcribe/app/
├─ MainActivity.kt      分頁 UI 與接收分享進來的檔案
├─ Screens.kt           即時轉寫、批次、設定三個畫面
├─ ViewModels.kt        即時轉寫的狀態、批次佇列與處理流程
├─ LiveTranscriber.kt   連續 SpeechRecognizer（自動重啟、錯誤處理、靜音提示音）
├─ AudioDecoder.kt      MediaCodec 解碼 → 16kHz 單聲道 WAV，並切段
├─ WhisperClient.kt     OpenAI 相容 /audio/transcriptions，含自動重試
├─ AppSettings.kt       設定
└─ Models.kt            逐字稿格式（TXT / SRT）與檔案工具
```
