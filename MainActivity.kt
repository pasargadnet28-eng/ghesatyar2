package ir.ghesatyar.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class MainActivity : Activity() {
    private data class Entry(
        val id: String,
        val type: String,
        val amount: Long,
        val note: String,
        val date: String, // Stored in ISO format: yyyy-MM-dd
        val createdAt: Long
    )

    private val prefs by lazy { getSharedPreferences("ghesatyar", MODE_PRIVATE) }
    private val entries = mutableListOf<Entry>()
    private var dailyTarget = 1_000_000L
    private var startDate = todayIso()
    private lateinit var root: LinearLayout
    private lateinit var statsBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private val fa = Locale("fa", "IR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.statusBarColor = 0xFFF5F7FA.toInt()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        loadData()
        buildScreen()
        refresh()
    }

    private fun isoFormat(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getDefault()
    }

    private fun todayIso(): String = isoFormat().format(Date())

    private fun money(n: Long): String = NumberFormat.getIntegerInstance(fa).format(n) + " تومان"

    private fun faDigits(s: String): String = s
        .replace('0', '۰').replace('1', '۱').replace('2', '۲').replace('3', '۳').replace('4', '۴')
        .replace('5', '۵').replace('6', '۶').replace('7', '۷').replace('8', '۸').replace('9', '۹')

    private fun enDigits(s: String): String = s
        .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3').replace('۴', '4')
        .replace('۵', '5').replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
        .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
        .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')

    // Convert Gregorian (yyyy-MM-dd) to Jalali (yyyy/MM/dd)
    private fun isoToJalali(iso: String): String {
        return try {
            val parts = iso.split("-")
            val gy = parts[0].toInt()
            val gm = parts[1].toInt()
            val gd = parts[2].toInt()
            val g_d_m = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
            val gy2 = if (gm > 2) gy + 1 else gy
            var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + g_d_m[gm - 1]
            var jy = -1595 + (33 * (days / 12053))
            days %= 12053
            jy += 4 * (days / 1461)
            days %= 1461
            if (days > 365) {
                jy += (days - 1) / 365
                days = (days - 1) % 365
            }
            val jm = if (days < 186) 1 + (days / 31) else 7 + ((days - 186) / 30)
            val jd = 1 + if (days < 186) (days % 31) else ((days - 186) % 30)
            String.format(Locale.US, "%04d/%02d/%02d", jy, jm, jd)
        } catch (_: Exception) {
            iso
        }
    }

    // Convert Jalali (jy, jm, jd) to Gregorian (yyyy-MM-dd) with validation
    private fun jalaliToIso(jy: Int, jm: Int, jd: Int): String? {
        if (jy < 1300 || jy > 1500 || jm !in 1..12) return null
        val maxDay = when {
            jm in 1..6 -> 31
            jm in 7..11 -> 30
            else -> if (isJalaliLeap(jy)) 30 else 29
        }
        if (jd !in 1..maxDay) return null

        val jy1 = jy + 1595
        var days = -355668 + (365 * jy1) + ((jy1 / 33) * 8) + (((jy1 % 33) + 3) / 4) + jd + if (jm < 7) (jm - 1) * 31 else ((jm - 7) * 30) + 186
        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            gy += 100 * (--days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val isGregLeap = (gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0)
        val sal_a = intArrayOf(0, 31, if (isGregLeap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        while (gm < 13 && gd > sal_a[gm]) {
            gd -= sal_a[gm]
            gm++
        }
        return String.format(Locale.US, "%04d-%02d-%02d", gy, gm, gd)
    }

    private fun isJalaliLeap(jy: Int): Boolean {
        val r = (jy + 1595) % 33
        return r == 1 || r == 5 || r == 9 || r == 13 || r == 17 || r == 22 || r == 26 || r == 30
    }

    // Parse user input date (accepts Jalali 1405/08/24 or 1405-08-24, or Gregorian 2026-11-15) into strict ISO yyyy-MM-dd
    private fun parseUserDateToIso(raw: String): String? {
        val clean = enDigits(raw.trim()).replace('/', '-')
        val parts = clean.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null

        return if (y in 1300..1500) {
            jalaliToIso(y, m, d)
        } else if (y in 1970..2100) {
            val formatted = String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
            try {
                isoFormat().parse(formatted)
                formatted
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    private fun daysPassed(): Long {
        return try {
            val sdf = isoFormat()
            val start = sdf.parse(startDate)!!.time
            val now = sdf.parse(todayIso())!!.time
            if (now < start) 0L else ((now - start) / 86_400_000L) + 1
        } catch (_: Exception) {
            1L
        }
    }

    private fun required(): Long = dailyTarget * daysPassed()
    private fun deposited(): Long = entries.filter { it.type == "in" }.sumOf { it.amount }
    private fun spent(): Long = entries.filter { it.type == "out" }.sumOf { it.amount }
    private fun net(): Long = deposited() - spent()
    private fun balanceGap(): Long = required() - net()

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFFF5F7FA.toInt())
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(42), dp(18), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF5F7FA.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("قسط‌یار", 26, 0xFF17324D.toInt(), true), lp(-1, -2, 0, 0, 0, 4))
        root.addView(text("مدیریت واریزی روزانه و هزینه‌های حساب اقساط", 13, 0xFF64748B.toInt()), lp(-1, -2, 0, 0, 0, 18))

        statsBox = cardColumn()
        root.addView(statsBox, lp(-1, -2, 0, 0, 0, 16))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val addIn = button("＋ ثبت واریزی", 0xFF16865B.toInt()) { addEntry("in") }
        val addOut = button("− ثبت هزینه", 0xFFC34B4B.toInt()) { addEntry("out") }
        actions.addView(addIn, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        actions.addView(addOut, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        root.addView(actions, lp(-1, -2, 0, 0, 0, 10))
        root.addView(button("⚙ تنظیم مبلغ روزانه و تاریخ شروع", 0xFF456783.toInt()) { settingsDialog() }, lp(-1, dp(48), 0, 0, 0, 18))

        root.addView(text("تراکنش‌ها", 19, 0xFF17324D.toInt(), true), lp(-1, -2, 0, 0, 0, 10))
        listBox = cardColumn()
        root.addView(listBox, lp(-1, -2))
        root.addView(text("اطلاعات به‌صورت آفلاین فقط روی همین گوشی ذخیره می‌شود.", 12, 0xFF8492A6.toInt()), lp(-1, -2, 0, 16, 0, 0))
    }

    private fun refresh() {
        statsBox.removeAllViews()
        val gap = balanceGap()
        statsBox.addView(text("وضعیت حساب تا امروز (${faDigits(isoToJalali(todayIso()))})", 16, 0xFF17324D.toInt(), true), lp(-1, -2, 0, 0, 0, 12))
        statRow("تاریخ شروع محاسبه", faDigits(isoToJalali(startDate)), 0xFF334155.toInt())
        statRow("مبلغ هدف روزانه", money(dailyTarget), 0xFF334155.toInt())
        statRow("تعداد روزهای محاسبه‌شده", faDigits(daysPassed().toString()) + " روز", 0xFF334155.toInt())
        statRow("مجموع مبلغی که باید تأمین می‌شد", money(required()), 0xFF334155.toInt())
        statRow("مجموع واریزی‌ها", money(deposited()), 0xFF16865B.toInt())
        statRow("مجموع هزینه‌ها", money(spent()), 0xFFC34B4B.toInt())
        statRow("مانده خالص واریزی‌ها", money(net()), 0xFF334155.toInt())

        val title = when {
            gap > 0 -> "مبلغ عقب‌ماندگی"
            gap < 0 -> "مبلغ جلوتر از برنامه"
            else -> "وضعیت برنامه"
        }
        val value = when {
            gap > 0 -> money(gap)
            gap < 0 -> money(-gap)
            else -> "برابر با برنامه"
        }
        val color = if (gap > 0) 0xFFC34B4B.toInt() else 0xFF16865B.toInt()
        val divider = View(this).apply { setBackgroundColor(0xFFE2E8F0.toInt()) }
        statsBox.addView(divider, lp(-1, dp(1), 0, 8, 0, 8))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(title, 15, color, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(value, 17, color, true))
        statsBox.addView(row)

        listBox.removeAllViews()
        if (entries.isEmpty()) {
            listBox.addView(text("هنوز تراکنشی ثبت نشده است.", 14, 0xFF8492A6.toInt()), lp(-1, -2))
        } else {
            entries.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.createdAt }).forEach { e ->
                val rowEntry = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    setPadding(0, dp(9), 0, dp(9))
                }
                val details = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val entryTitle = if (e.type == "in") {
                    "واریزی${if (e.note.isNotBlank()) " - ${e.note}" else ""}"
                } else {
                    "هزینه${if (e.note.isNotBlank()) " - ${e.note}" else ""}"
                }
                details.addView(text(entryTitle, 14, 0xFF334155.toInt(), true))
                details.addView(text(faDigits(isoToJalali(e.date)), 12, 0xFF8492A6.toInt()))
                rowEntry.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                rowEntry.addView(
                    text(
                        (if (e.type == "in") "+ " else "− ") + money(e.amount),
                        13,
                        if (e.type == "in") 0xFF16865B.toInt() else 0xFFC34B4B.toInt(),
                        true
                    )
                )
                rowEntry.setOnLongClickListener { confirmDelete(e); true }
                listBox.addView(rowEntry)
                val line = View(this).apply { setBackgroundColor(0xFFEFF2F6.toInt()) }
                listBox.addView(line, lp(-1, dp(1)))
            }
            listBox.addView(text("برای حذف یک تراکنش، انگشت را روی آن نگه دارید.", 11, 0xFF8492A6.toInt()), lp(-1, -2, 0, 8, 0, 0))
        }
    }

    private fun statRow(label: String, value: String, color: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(text(label, 13, 0xFF64748B.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(value, 13, color, true))
        statsBox.addView(row)
    }

    private fun addEntry(type: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val amount = EditText(this).apply {
            hint = "مبلغ به تومان"
            inputType = InputType.TYPE_CLASS_NUMBER
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.RIGHT
        }
        val note = EditText(this).apply {
            hint = "شرح (اختیاری؛ مثلاً خرید یا حقوق)"
            gravity = Gravity.RIGHT
        }
        val date = EditText(this).apply {
            setText(faDigits(isoToJalali(todayIso())))
            hint = "تاریخ شمسی (مثلاً ۱۴۰۵/۰۸/۲۴)"
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.RIGHT
        }
        box.addView(amount)
        box.addView(note)
        box.addView(date)

        AlertDialog.Builder(this)
            .setTitle(if (type == "in") "ثبت واریزی" else "ثبت هزینه")
            .setView(box)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ثبت") { _, _ ->
                val cleanAmount = enDigits(amount.text.toString().trim()).replace(",", "").replace("٬", "")
                val value = cleanAmount.toLongOrNull()
                if (value == null || value <= 0) {
                    toast("مبلغ معتبر وارد کنید")
                    return@setPositiveButton
                }
                val isoDate = parseUserDateToIso(date.text.toString())
                if (isoDate == null) {
                    toast("تاریخ معتبر نیست (نمونه: ۱۴۰۵/۰۸/۲۴)")
                    return@setPositiveButton
                }
                entries.add(
                    Entry(
                        id = UUID.randomUUID().toString(),
                        type = type,
                        amount = value,
                        note = note.text.toString().trim(),
                        date = isoDate,
                        createdAt = System.currentTimeMillis()
                    )
                )
                saveData()
                refresh()
            }.show()
    }

    private fun settingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val target = EditText(this).apply {
            hint = "مبلغ موردنیاز روزانه به تومان"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(dailyTarget.toString())
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.RIGHT
        }
        val start = EditText(this).apply {
            hint = "تاریخ شروع (مثلاً ۱۴۰۵/۰۸/۲۴)"
            setText(faDigits(isoToJalali(startDate)))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.RIGHT
        }
        box.addView(text("مبلغی که هر روز باید برای قسط تأمین شود", 13, 0xFF64748B.toInt()))
        box.addView(target)
        box.addView(text("از چه تاریخی محاسبه روزانه شروع شود؟ (شمسی)", 13, 0xFF64748B.toInt()), lp(-1, -2, 0, 12, 0, 0))
        box.addView(start)

        AlertDialog.Builder(this)
            .setTitle("تنظیمات حساب")
            .setView(box)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val cleanTarget = enDigits(target.text.toString().trim()).replace(",", "").replace("٬", "")
                val t = cleanTarget.toLongOrNull()
                val isoStart = parseUserDateToIso(start.text.toString())
                if (t == null || t <= 0 || isoStart == null) {
                    toast("مبلغ یا تاریخ معتبر نیست")
                    return@setPositiveButton
                }
                dailyTarget = t
                startDate = isoStart
                saveData()
                refresh()
            }.show()
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setMessage("این تراکنش حذف شود؟")
            .setNegativeButton("خیر", null)
            .setPositiveButton("حذف") { _, _ ->
                entries.removeAll { it.id == e.id }
                saveData()
                refresh()
            }.show()
    }

    private fun loadData() {
        dailyTarget = prefs.getLong("target", 1_000_000L)
        if (!prefs.contains("start")) {
            prefs.edit().putString("start", todayIso()).apply()
        }
        startDate = prefs.getString("start", todayIso()) ?: todayIso()
        entries.clear()
        try {
            val arr = JSONArray(prefs.getString("entries", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    Entry(
                        id = o.getString("id"),
                        type = o.getString("type"),
                        amount = o.getLong("amount"),
                        note = o.optString("note"),
                        date = o.getString("date"),
                        createdAt = o.optLong("createdAt", i.toLong())
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun saveData() {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("type", e.type)
                    .put("amount", e.amount)
                    .put("note", e.note)
                    .put("date", e.date)
                    .put("createdAt", e.createdAt)
            )
        }
        prefs.edit()
            .putLong("target", dailyTarget)
            .putString("start", startDate)
            .putString("entries", arr.toString())
            .apply()
    }

    private fun cardColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(0xFFFFFFFF.toInt())
        elevation = dp(2).toFloat()
    }

    private fun text(s: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = s
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.RIGHT
        textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun button(s: String, color: Int, action: () -> Unit): Button = Button(this).apply {
        text = s
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(color)
        isAllCaps = false
        textSize = 14f
        setOnClickListener { action() }
    }

    private fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
