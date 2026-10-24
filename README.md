# Nibras Office

Android üçün oflayn PDF redaktoru (Kotlin, minSdk 26).

## İmkanlar (2.2.0)
- WPS üslubunda davamlı şaquli oxuyucu, tam ekran (toxun — panel görünür/gizlənir)
- Üst panel: tablar (Ana Səhifə, Əlavə et, Redaktə, Şərhlər, Çevir, Alətlər) və lentlər
- Qələm rejimi: aşağıda üzən alət həbi (qələm, marker, pozan, seç, rəng, fiqur, əl, tam ekran)
- Şifrəli PDF-ləri açmaq, şifrəni saxlamaq/dəyişmək/silmək
- Mətn və qeydlər PDF-ə seçilə bilən real mətn kimi yazılır (DejaVu Sans, ərəb shaping)
- Səhifə əməliyyatları və birləşdirmə geri alına bilir
- OCR (Tesseract): skan edilmiş PDF-lərdə axtarış + görünməz mətn qatı

- PDF açmaq: fayl seçici, «Bununla aç», «Paylaş»; son açılanlar, qaldığı səhifə
- Oxumaq: yaxınlaşdırma/sürüşdürmə, səhifə çevirmə, gecə rejimi, səhifəyə keçid
- Qeydlər: qələm, marker, mətn, fiqurlar (xətt, ox, düzbucaqlı, elips), pozan, yapışqan qeyd
- Şəkil və imza əlavə etmək (sürüşdürmək, küncdən böyütmək), saxlanmış imza
- Geri al / təkrar et (yerdəyişmə və mətn dəyişikliyi daxil)
- Mətndə axtarış (nəticələr vurğulanır, növbəti/əvvəlki)
- Səhifələr: döndərmək, köçürmək, dublikat, boş səhifə, silmək; PDF birləşdirmək, bölmək, səhifə çıxarmaq
- Yadda saxla (orijinal fayla), Fərqli saxla, Paylaş, Çap et, səhifələri şəkil kimi saxla/paylaş
- Qeydlər PDF-ə vektor kimi yazılır (orijinal mətn seçilə bilən qalır)

Kitabxana: [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0).

## Yığım
`gradle assembleDebug` — GitHub Actions hər push-da test APK yığır (artifact: `NibrasPDF-debug-apk`).
