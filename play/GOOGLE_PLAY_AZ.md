# Nibras PDF — Google Play hazırlığı

Yoxlanılıb: 21.10.2026 (Play qaydaları bu tarixə görədir; Play Console-un sualları sonra dəyişə bilər).

## Layihədə artıq hazır olanlar
- **Target API 36 (Android 16):** Google 31.08.2026-dan yeni tətbiq və yeniləmələrdə bunu tələb edir.
- **AAB** (Play-in tələb etdiyi format) + R8 kiçiltmə. `versionCode` hər GitHub run-da avtomatik artır.
- **İmza:** upload açarı GitHub Secrets-dən oxunur, açar repo-ya düşmür.
- **İcazə yoxdur** (INTERNET də yoxdur) → Data safety-də "məlumat toplanmır".
- Android 15/16 tələbləri (tam ekran / edge-to-edge, yeni "geri" jesti) tətbiqdə nəzərə alınıb.

## Addımlar

### 1) Play Console hesabı
- Hesab yoxdursa: play.google.com/console (birdəfəlik qeydiyyat haqqı var).
- **Şəxsi hesab** 13.11.2023-dən sonra açılıbsa, production-dan əvvəl **closed test** məcburidir: ən azı **12 test istifadəçisi, 14 gün fasiləsiz** opt-in olmalıdır, sonra "production access" üçün müraciət edirsən. **Təşkilat hesabı** bu qaydadan azaddır.

### 2) Upload açarı (yalnız bir dəfə)
Termux-da:
```
pkg install openjdk-17
bash play/make-keystore.sh
```
Skript `upload.jks` yaradır və 4 dəyər çap edir. GitHub → repo → Settings → Secrets and variables → Actions → New repository secret:
`KEYSTORE_BASE64` (upload.jks.base64.txt faylının məzmunu), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
`upload.jks` və şifrələri 2 təhlükəsiz yerdə saxla. Repo-ya yükləmə (`.gitignore` onsuz da bloklayır).

### 3) AAB qur
GitHub → Actions → **Release (Play AAB)** → Run workflow (≈ 6–8 dəq).
Bitəndə run səhifəsində **Artifacts → NibrasPDF-release** → içində `app-release.aab` (Play-ə bu yüklənir).
İmzalı `app-release.apk` isə Releases bölməsində olur (telefonda sınamaq üçün).
Əvvəlcə **Build APK (test)** workflow-u ilə telefonda yoxla; debug APK Play-ə yüklənmir.

### 4) Play Console-da tətbiqi yarat
Create app → ad: `Nibras PDF`, dil, **App**, **Free**, bəyannamələri təsdiqlə.
Paket adı `com.nibrascode.pdf` ilk yükləmədən sonra dəyişdirilə bilmir.

### 5) "App content" bölməsi (hamısı doldurulmalıdır)
| Bölmə | Cavab |
|---|---|
| Privacy policy | `play/privacy-policy.html` faylını saytına yüklə (məs. nibrascode.com/pdf/privacy.html), `__EMAIL__` yerinə e-poçtunu yaz, linki bura qoy. Link ictimai olmalı, login və PDF olmamalıdır |
| Ads | Yox |
| App access | Bütün funksiyalar qeydiyyatsız açıqdır |
| Data safety | "Does your app collect or share any of the required user data types?" → **No**. (Google bu formu məlumat toplamayan tətbiqlər üçün də tələb edir) |
| Content rating | Kateqoriya: Utility / Productivity. Bütün suallara **No** → gözlənilən reytinq: hamı üçün |
| Target audience | 18+ (uşaqlara yönəlməyib) |
| News / COVID-19 / Government / Financial / Health | Hamısı: Yox |
| Advertising ID | İstifadə olunmur |

### 6) Store listing
Aşağıdakı mətnləri yapışdır. Qrafiklər bu qovluqdadır:
- **App icon:** `play/icon-512.png` (512×512)
- **Feature graphic:** `play/feature-1024x500.png` (1024×500)
- **Phone screenshots:** ən azı 2 (tövsiyə: 4–8). Hər tərəf 320–3840 px, PNG/JPEG. Real tətbiqdən çək (Play saxta ekran görüntülərini qəbul etmir). Təklif olunan kadrlar: ana səhifə (son açılanlar), oxuma, qələm ilə yazı, marker, mətn, fiqurlar, gecə rejimi.
- Kateqoriya: **Productivity**. Əlaqə e-poçtu mağaza səhifəsində ictimai görünür.
- Adda və təsvirdə WPS, Adobe və s. ticarət nişanlarını işlətmə.

#### Azərbaycan dili
**Ad (≤30):** Nibras PDF – Oxu və Qeyd et

**Qısa təsvir (≤80):** PDF-ləri oxu, qələmlə yaz, markerlə rənglə. Reklamsız və oflayn.

**Tam təsvir:**

Nibras PDF — PDF sənədlərini telefonda rahat oxumaq və üzərinə qeyd etmək üçün sürətli, sadə və məxfi tətbiqdir.

NƏ EDƏ BİLƏRSƏN
• PDF-i aç və oxu: iki barmaqla yaxınlaşdır, iki dəfə toxunaraq sürətli zoom et
• Qələm ilə sərbəst yaz və çək
• Marker ilə mətnin üstünü rəngləyib vurğula
• İstənilən yerə mətn yaz — Azərbaycan hərfləri (ə, ı, ö, ü, ğ, ş, ç) tam dəstəklənir; mətni sürüşdürüb yerini dəyiş
• Xətt, ox, düzbucaqlı və elips çək
• Pozan ilə lazımsız qeydləri sil; Geri al və Təkrar et
• 7 rəng və sürüşdürücü ilə qalınlıq/ölçü seçimi
• Gecə rejimi ilə gözlərini yormadan oxu
• Son açılan sənədlər siyahısı: qeydlərin avtomatik saxlanılır, qaldığın yerdən davam edə bilərsən
• Nəticəni yeni PDF faylı kimi saxla və paylaş

MƏXFİLİK
• Hesab, qeydiyyat və reklam yoxdur
• Tətbiq internetə qoşulmur — sənədlərin yalnız sənin cihazında qalır

QEYD
Saxlanan PDF nüsxəsində səhifələr şəkil formatında olur (mətn seçmək mümkün deyil). Parollu PDF-lər hələlik dəstəklənmir.

#### English
**Title (≤30):** Nibras PDF – Read & Annotate

**Short description (≤80):** Read PDFs, write on them, highlight and draw. Ad-free and offline.

**Full description:**

Nibras PDF is a fast, simple and private app for reading PDF documents on your phone and marking them up.

WHAT YOU CAN DO
• Open and read PDFs: pinch to zoom, double-tap for quick zoom
• Write and draw freely with the pen
• Highlight text with the marker
• Add text anywhere (full Unicode support, including Azerbaijani letters) and drag it into place
• Draw lines, arrows, rectangles and ellipses
• Erase annotations; undo and redo
• 7 colors and a slider for stroke width / text size
• Night mode for comfortable reading
• Recent documents list: your annotations are saved automatically so you can continue where you left off
• Save the result as a new PDF file and share it

PRIVACY
• No account, no sign-up, no ads
• The app never connects to the internet — your documents stay on your device

NOTE
In the saved PDF copy, pages are stored as images (text cannot be selected). Password-protected PDFs are not supported yet.

### 7) Test və yayım
1. Testing → **Closed testing** → yeni release → `app-release.aab` yüklə.
2. Test istifadəçilərini e-poçt siyahısı və ya Google Group ilə əlavə et, opt-in linkini göndər. (Şəxsi hesabda: 12+ nəfər, 14 gün fasiləsiz.)
3. Şərt ödənəndə Dashboard-da **Apply for production access** → sonra Production release.
4. Təşkilat hesabında closed test məcburi deyil, birbaşa Production-a çıxmaq olar.

### 8) Yeniləmə
Kodu dəyiş → push → Actions → **Release (Play AAB)** → yeni `.aab` → Play Console-da yeni release. `versionCode` avtomatik artır.

## Qeydlər
- İmzalı release APK-nı telefona qurmusansa, sonra Play-dən qurmaq üçün əvvəlcə onu sil (imzalar fərqlidir).
- Play App Signing-i söndürmə: Google əsas imza açarını saxlayır, sən yalnız upload açarı ilə yükləyirsən.
- Saxlanan PDF-də səhifələr şəkil kimi yazılır. Bu mağaza təsvirində "QEYD" bölməsində açıq deyilir, silmə.
