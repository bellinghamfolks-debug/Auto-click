# مكفوف بوت 2.0 — Mokafeefah Clicker

> 🇸🇦 **بوت ذكي يعتمد على خدمة Accessibility لقراءة شجرة عناصر أي تطبيق والنقر على الأزرار التي يحددها المستخدم بنفسه (مثل "إهتمام"، "نعم"، "إغلاق").**
>
> الإصدار 2.0 يحل مشكلة البطء التراكمي الذي كان يصل لـ "عضو كل دقيقة" بإعادة بناء المحرك من الصفر.

---

## ✨ الجديد في الإصدار 2.0

| التحديث | الوصف |
|---|---|
| 🧠 **Smart Scan & Recycle** | `recycle()` لكل AccessibilityNodeInfo + نافذة منزلقة لذاكرة العناصر (60 عنصر) — يحافظ على سرعة فحص ثابتة |
| ⚡ **Event-Driven Automation** | `onAccessibilityEvent` يلتقط `TYPE_WINDOW_STATE_CHANGED` فيستدعي tick فوراً بدل انتظار 300ms |
| 🎯 **Filtering Logic** | كلمات قابلة للتخصيص: "مضاف سابقاً"، "لا يمكن الإضافة"، "تم الإرسال" — تجاوز فوري |
| 📜 **Dynamic Scrolling** | `ACTION_SCROLL_FORWARD` على عقدة القائمة + fallback بمسافة سحب متكيّفة |
| 🛡️ **Watchdog Timer** | 15 ثانية تجمد ⇒ GLOBAL_ACTION_BACK + scroll تلقائي لإعادة التنشيط |
| ⚙️ **إعدادات جديدة** | حد التوقف، الوضع السريع، الاهتزاز، المحرك المستند للأحداث |

---

## 🛠️ كيف يعمل

1. تفتح التطبيق وتحدد كلمات أزرار الإعجاب والتأكيد والإغلاق.
2. تضغط زر "فتح إعدادات إمكانية الوصول" وتفعّل الخدمة من إعدادات النظام.
3. تضغط "▶ بدء التشغيل" ثم تفتح التطبيق الهدف.
4. البوت يقرأ الشاشة ويبحث عن نصوص الأزرار وينقرها لحظياً.
5. عند ظهور رسالة خطأ يتم تخطي العضو فوراً، وعند التجمد يقوم بـ Back ثم scroll تلقائياً.

---

## 📦 البناء التلقائي عبر GitHub Actions

كل push على فرع `main` ينشئ APK جديد تلقائياً:
- اسم الملف: `mokafeefah-clicker-v2.0-debug.apk`
- يُنشر كـ artifact في الـ Action، ويُصدر تلقائياً عند `workflow_dispatch`.

### البناء المحلي
```bash
cd android
gradle assembleDebug
# APK at: android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 📁 هيكل المشروع

```
android/
├── build.gradle, settings.gradle, gradle.properties
└── app/
    ├── build.gradle  (versionName=2.0, versionCode=2)
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/mokafeefah/clicker/
        │   ├── MainActivity.java    — شاشة الإعدادات
        │   └── ClickerService.java  — المحرك (التحديثات الـ 5)
        └── res/
            ├── layout/activity_main.xml
            ├── values/{strings,colors,themes}.xml
            └── xml/accessibility_service_config.xml
```

---

## ⚠️ تنبيه

البوت يعمل على أي تطبيق يحدده المستخدم بنفسه عبر النصوص. لا يتجاوز حماية أي تطبيق ولا يستخدم إحداثيات ثابتة ولا يحتاج Root.

استخدمه بمسؤولية ومراعاة شروط استخدام التطبيقات المستهدفة.
