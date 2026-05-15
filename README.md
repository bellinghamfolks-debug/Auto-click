# مكفوف بوت 2.0 — Mokafeefah Clicker

> 🇸🇦 **بوت يعتمد على Accessibility Service لقراءة شجرة عناصر التطبيق والنقر على الأزرار التي يحددها المستخدم بنفسه.**

الإصدار **2.0** = منطق الإصدار الأصلي الذي كان يعمل بشكل صحيح + 5 إصلاحات هندسية لحل مشكلة البطء التراكمي.

---

## ✅ ما تم الحفاظ عليه من الإصدار 1 (المنطق الأساسي)

- **State machine** بـ 4 حالات: `LOOK_LIKE → AFTER_LIKE → AFTER_YES → MUST_SCROLL`
- **حقول النص كلمة واحدة فقط**: "إهتمام"، "نعم"، "إغلاق" (وليس قوائم مفصولة بفواصل)
- **normalizeArabic** لتطبيع الهمزة (أ/إ/آ→ا)، الياء/الألف المقصورة (ى→ي)، التاء المربوطة (ة→ه) والتشكيل
- **collectAllRoots** لجمع شجرة العناصر من **كل** النوافذ (popups في نوافذ منفصلة)
- **boundsKey** بشبكة 80px لمنع إعادة نقر نفس العنصر بعد التمرير
- **getInstance() + StatusListener** للتواصل المباشر بين Activity و Service

## 🆕 الإصلاحات الـ 5 (مضافة بشكل غير مدمر)

| # | الإصلاح | كيف يعمل |
|---|---|---|
| 1 | 🧠 **Smart Recycle** | لا يُحتفَظ بمراجع العقد بين الـ ticks، يُنظَّف `processedBounds` بعد كل scroll |
| 2 | ⚡ **Event-Driven** (اختياري) | عند تفعيل المفتاح: يستجيب لـ `TYPE_WINDOW_STATE_CHANGED` **فقط أثناء AFTER_LIKE/AFTER_YES** مع debounce 400ms — آمن تماماً |
| 3 | 🎯 **Filtering** | عند ظهور "مضاف سابقاً" أو "لا يمكن الإضافة" → نقر إغلاق + scroll فوري |
| 4 | 📜 **Dynamic Scroll** | مسافة scroll متكيّفة (تقل عند النجاح، تزيد عند العلق) |
| 5 | 🛡️ **Watchdog** | عند العلق 15 ثانية → BACK + scroll قوي **قبل** الإيقاف التلقائي |

## ⚙️ الإعدادات الجديدة

- 📝 **كلمات رسائل الخطأ** — قائمة مفصولة بفواصل (Filtering)
- ⏱️ **مهلة Watchdog** (ثانية) — افتراضي 15
- 🎯 **حد التوقف عند العدد** — 0 = لا حد
- 🔔 **مفتاح الاهتزاز** عند الإيقاف والنجاح
- ⚡ **مفتاح الوضع السريع** — يقلل الـ scan interval بمقدار 50ms
- 🎬 **مفتاح Event-Driven** — معطل افتراضياً، لا تفعّله إلا بعد التجربة

---

## 🚀 البناء التلقائي

كل push على فرع `main` يُنتج: `mokafeefah-clicker-v2.0-debug.apk`

### البناء المحلي
```bash
cd android && gradle assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📁 هيكل المشروع

```
android/app/src/main/
├── AndroidManifest.xml
├── java/com/mokafeefah/clicker/
│   ├── MainActivity.java    — شاشة الإعدادات (getInstance + StatusListener)
│   └── ClickerService.java  — المحرك (State machine + 5 fixes)
└── res/
    ├── layout/activity_main.xml
    ├── values/{strings,colors,themes}.xml
    └── xml/accessibility_service_config.xml
```

## 🔧 نصائح الاستخدام

1. أبقِ Event-Driven **معطلاً** في أول تجربة — المنطق الأصلي يعمل بشكل ممتاز بدونه
2. إذا أردت سرعة أكبر بعد التأكد من ثبات البوت، فعّل Event-Driven
3. كلمات رسائل الخطأ افتراضياً تغطي أكثر الحالات شيوعاً — أضف ما يلزم
4. عند ظهور سلوك غريب: اضغط **استعادة الإعدادات الافتراضية** ثم جرّب من جديد
