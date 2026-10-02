package com.chinut.bawantv.core

import com.chinut.bawantv.BawanApp

/**
 * 未成年人保护（家长控制）。
 *
 * ## 为什么不能用「一个分级字段」解决
 *
 * 已实测三个内容源返回的元数据：
 *  - 低端影视：`type`（电影/剧集/综艺/动漫）、`genres`（爱情/剧情/古装…）、
 *    `region`、`rating`（豆瓣分）、`intro`
 *  - TVBox 订阅：`typeName`（分类名）、`area`（地区）、`year`、`score`
 *  - 央视频直播：只有频道名
 *
 * **没有任何一家返回年龄分级**（没有 PG-13 / TV-MA 这类字段）。
 * 所以「能不能看」只能靠本地规则做启发式判断，不可能像自带分级的平台那样精确。
 *
 * ## 判定顺序（从宽到严，命中即返回）
 *
 *  1. **白名单**：分类/类型名里出现「动漫/少儿/亲子/益智/科教/纪录/动画」→ 直接放行
 *     （动画类里有成人向作品，所以白名单也要过一遍黑名单，见 [evaluate]）
 *  2. **黑名单**：标题/简介/类型里出现「恐怖/惊悚/情色/三级/暴力/吸毒/赌/血腥/犯罪」
 *     等关键词 → 拦
 *  3. **年龄线**：低端影视的 `rating` 与 `type` 做粗判（例如「综艺」里的情感类）
 *  4. **家长手工名单**：黑名单里的词/白名单里的词优先级最高，用来兜住前三条的误判
 *
 * ## 诚实的边界
 *
 * 这是**关键词 + 分类的启发式规则，不能保证拦全**。它拦得住明显的，拦不住
 * 把成人内容包装成普通剧情的情况。真正的兜底要靠家长用 [ParentalControl.addBlockedWord]
 * 把具体片名加进黑名单。UI 上必须把这句话讲清楚，不能让家长以为是万无一失的。
 *
 * ## 关于开关本身
 *
 * 开关由 [ParentalControl.unlocked] 保护：设置这个开关需要先过 PIN，
 * 否则孩子自己点开设置就关掉了，「保护」就是假的。
 */
object ParentalControl {

    /** 打开开关时要求设置的 PIN 长度。 */
    const val PIN_LENGTH = 4

    // ==================== 开关本身 ====================

    private fun prefs() = BawanApp.prefs

    /** 是否开启未成年人保护。 */
    val enabled: Boolean get() = prefs().parentalEnabled

    /**
     * 当前这一次操作是否已通过 PIN 校验。
     *
     * 只是**会话内**的临时放行（内存变量，不进磁盘），
     * 所以 App 重启后又要重新输一次 —— 这正是我们要的：
     * 孩子重启 App 也绕不过去。
     */
    @Volatile
    var unlocked: Boolean = false
        private set

    /** 是否已经设置过 PIN。 */
    val hasPin: Boolean get() = prefs().parentalPin.isNotBlank()

    /** 校验 PIN。正确则把本次会话标记为已解锁。 */
    fun unlock(pin: String): Boolean {
        if (pin == prefs().parentalPin) {
            unlocked = true
            return true
        }
        return false
    }

    /** 关闭保护并回到未解锁状态（例如家长离开客厅时手动锁上）。 */
    fun lock() {
        unlocked = false
    }

    /** 设置/修改 PIN（由设置页在已解锁的前提下调用）。 */
    fun setPin(pin: String) {
        prefs().parentalPin = pin
        unlocked = true
    }

    /** 切换保护开关。 */
    fun setEnabled(on: Boolean) {
        prefs().parentalEnabled = on
        if (!on) unlocked = true
    }

    // ==================== 分类与关键词规则 ====================

    /**
     * 白名单：出现这些词的内容优先视为适合未成年人。
     *
     * 刻意做得保守 —— 只放真正面向家庭/儿童/科普的类别，
     * 而不是「所有动画」（动画里有大量成人向作品）。
     */
    private val ALLOW_WORDS = listOf(
        "少儿", "儿童", "亲子", "益智", "早教", "启蒙", "动画", "动漫",
        "科教", "科普", "纪录", "纪录", "自然", "动物", "历史", "人文",
        "家庭", "合家欢", "童话", "冒险", "体育", "音乐", "戏曲", "相声",
    )

    /**
     * 黑名单：出现这些词的内容一律拦下。
     *
     * 这些词覆盖了常见的「明显不适合」类别。要强调的是：
     * **命中率取决于标题/分类是否诚实**，包装过的内容拦不住，所以还有手工名单兜底。
     */
    private val BLOCK_WORDS = listOf(
        "恐怖", "惊悚", "血腥", "暴力", "情色", "色情", "三级", "限制级",
        "犯罪", "黑帮", "毒品", "吸毒", "赌", "凶杀", "谋杀", "尸",
        "情欲", "偷情", "出轨", "伦理", "复仇", "杀戮", "战争", "枪战",
        "鬼", "灵异", "丧尸", "僵尸", "邪教", "自杀", "虐",
    )

    /** 判定结果。 */
    enum class Verdict {
        /** 允许观看。 */
        Allowed,

        /** 拦下。 */
        Blocked,
    }

    /**
     * 判断一条内容能不能给孩子看。
     *
     * @param title 标题
     * @param typeName 分类/类型名（VOD 的 typeName、低端影视的 type 或 genres 拼接）
     * @param extra 额外参与判断的文本（简介、地区等），可为空
     */
    fun evaluate(
        title: String,
        typeName: String = "",
        extra: String = "",
    ): Verdict {
        if (!enabled || unlocked) return Verdict.Allowed

        // 家长手工名单优先级最高：先看拦、再看放行
        val manualBlocked = prefs().parentalBlockedWords
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (manualBlocked.any { title.contains(it, ignoreCase = true) }) {
            return Verdict.Blocked
        }
        val manualAllowed = prefs().parentalAllowedWords
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (manualAllowed.any { title.contains(it, ignoreCase = true) }) {
            return Verdict.Allowed
        }

        val haystack = "$title $typeName $extra"

        // 黑名单优先于白名单：动画里也有成人向的，「动漫」不能当免死金牌
        if (BLOCK_WORDS.any { haystack.contains(it, ignoreCase = true) }) {
            return Verdict.Blocked
        }

        if (ALLOW_WORDS.any { haystack.contains(it, ignoreCase = true) }) {
            return Verdict.Allowed
        }

        // 两条规则都没命中：**默认拦**。
        // 家长控制应该"默认保守"——不确定就不放，否则等于没开。
        // 想放得宽可以往白名单里加词。
        return Verdict.Blocked
    }

    /** 是否允许观看（供 UI 直接用的语法糖）。 */
    fun allows(title: String, typeName: String = "", extra: String = ""): Boolean =
        evaluate(title, typeName, extra) == Verdict.Allowed

    /**
     * 当前是否处于「锁定」状态 —— 也就是保护开着、且这次会话还没输过 PIN。
     * UI 用它来决定要不要显示锁标识、以及拦下时弹不弹 PIN 框。
     */
    val locked: Boolean get() = enabled && !unlocked

    // ==================== 手工名单 ====================

    fun addBlockedWord(word: String) {
        val cur = prefs().parentalBlockedWords.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (word.isNotBlank() && word !in cur) {
            prefs().parentalBlockedWords = (cur + word.trim()).joinToString(",")
        }
    }

    fun addAllowedWord(word: String) {
        val cur = prefs().parentalAllowedWords.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (word.isNotBlank() && word !in cur) {
            prefs().parentalAllowedWords = (cur + word.trim()).joinToString(",")
        }
    }

    fun clearWordLists() {
        prefs().parentalBlockedWords = ""
        prefs().parentalAllowedWords = ""
    }
}
