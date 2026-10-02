package io.github.xororz.localdream.util

/**
 * Offline Chinese -> Stable-Diffusion-prompt helper.
 *
 * SD1.5 text encoders only understand English tags. Sending raw Chinese produces
 * garbage tokens, so when the prompt contains CJK characters we:
 *   1) split on punctuation / spaces,
 *   2) replace known Chinese words/phrases with English tags,
 *   3) append a small quality boost,
 * without any network dependency or added latency.
 *
 * Unknown Chinese words are dropped (they would map to nothing anyway); numbers
 * and latin tokens are kept verbatim. This covers the common vocabulary people
 * actually type (subject / scene / style / quality). It is intentionally a
 * pragmatic tag translator, not a full MT engine.
 */
object ChinesePrompt {

    private val DICT: LinkedHashMap<String, String> = linkedMapOf(
        // quality / boosters
        "最高画质" to "best quality, ultra detailed",
        "高质量" to "high quality, highly detailed",
        "画质精美" to "masterpiece, best quality",
        "杰作" to "masterpiece",
        "高清" to "high resolution, sharp focus",
        "超高清" to "ultra high resolution, 8k",
        "极致细节" to "intricate details, ultra detailed",
        "细节丰富" to "rich details",
        "电影感" to "cinematic, cinematic lighting",
        "电影级" to "cinematic shot, dramatic lighting",
        "光影" to "dramatic light and shadow",
        "写实" to "photorealistic, realistic",
        "真实" to "realistic, lifelike",
        "照片级" to "photorealistic, photo realistic",
        "真实感" to "realistic",
        "精美" to "exquisite, beautiful detailed",
        "唯美" to "aesthetic, beautiful",

        // styles
        "动漫" to "anime style",
        "动画" to "anime style",
        "二次元" to "anime style, 2d",
        "卡通" to "cartoon style",
        "赛博朋克" to "cyberpunk",
        "蒸汽朋克" to "steampunk",
        "古风" to "chinese ancient style",
        "国风" to "chinese style",
        "水墨画" to "chinese ink painting",
        "油画" to "oil painting",
        "水彩" to "watercolor",
        "素描" to "pencil sketch",
        "像素风" to "pixel art",
        "概念艺术" to "concept art",
        "奇幻" to "fantasy art",
        "科幻" to "sci-fi",
        "吉卜力" to "ghibli style",

        // camera / composition
        "特写" to "close-up shot",
        "近景" to "close-up",
        "全景" to "wide shot, panoramic",
        "广角" to "wide angle lens",
        "仰拍" to "low angle shot",
        "俯拍" to "high angle shot, aerial view",
        "鸟瞰" to "bird's eye view",
        "虚化" to "bokeh, depth of field",
        "景深" to "depth of field",
        "逆光" to "backlighting",
        "夜景" to "night scene, night",
        "黄昏" to "sunset, dusk",
        "日出" to "sunrise",
        "蓝天" to "blue sky",
        "白云" to "white clouds",

        // subjects: people
        "女孩" to "1girl",
        "少女" to "1girl, young girl",
        "男孩" to "1boy",
        "少年" to "1boy",
        "女人" to "1woman",
        "男人" to "1man",
        "女性" to "female",
        "男性" to "male",
        "美女" to "beautiful woman, pretty face",
        "帅哥" to "handsome man",
        "小孩" to "child",
        "儿童" to "child",
        "婴儿" to "baby",
        "长发" to "long hair",
        "短发" to "short hair",
        "双马尾" to "twintails",
        "马尾" to "ponytail",
        "白发" to "white hair",
        "金发" to "blonde hair",
        "黑发" to "black hair",
        "蓝发" to "blue hair",
        "红眼" to "red eyes",
        "蓝眼" to "blue eyes",
        "绿眼" to "green eyes",
        "微笑" to "smile",
        "笑容" to "smiling",
        "眼睛" to "detailed eyes",
        "可爱" to "cute, kawaii",
        "萌" to "cute, kawaii",
        "漂亮" to "beautiful",
        "帅气" to "handsome",
        "穿" to "wearing",
        "连衣裙" to "dress",
        "裙子" to "skirt",
        "校服" to "school uniform",
        "制服" to "uniform",
        "西装" to "suit",
        "和服" to "kimono",
        "汉服" to "hanfu",
        "卫衣" to "hoodie",
        "泳衣" to "swimsuit",
        "帽子" to "hat",
        "眼镜" to "glasses",
        "猫耳" to "cat ears",
        "兔耳" to "bunny ears",
        "翅膀" to "wings",
        "天使" to "angel",
        "恶魔" to "demon",
        "精灵" to "elf",
        "公主" to "princess",
        "骑士" to "knight",
        "武士" to "samurai",
        "忍者" to "ninja",
        "机甲" to "mecha",

        // subjects: animals / creatures
        "猫" to "cat",
        "猫咪" to "cat",
        "小猫" to "kitten",
        "狗" to "dog",
        "小狗" to "puppy",
        "鸟" to "bird",
        "鱼" to "fish",
        "马" to "horse",
        "兔子" to "rabbit",
        "狐狸" to "fox",
        "狼" to "wolf",
        "龙" to "dragon",
        "老虎" to "tiger",
        "狮子" to "lion",
        "熊猫" to "panda",
        "蝴蝶" to "butterfly",
        "花" to "flower",
        "樱花" to "cherry blossom",
        "玫瑰" to "rose",
        "树" to "tree",
        "森林" to "forest",
        "山" to "mountain",
        "山脉" to "mountains",
        "湖" to "lake",
        "湖泊" to "lake",
        "河流" to "river",
        "河" to "river",
        "大海" to "sea, ocean",
        "海" to "ocean",
        "海边" to "seaside, beach",
        "海滩" to "beach",
        "沙滩" to "sandy beach",
        "瀑布" to "waterfall",
        "草原" to "grassland, meadow",
        "花田" to "flower field",
        "天空" to "sky",
        "星空" to "starry sky",
        "星星" to "stars",
        "月亮" to "moon",
        "太阳" to "sun",
        "云" to "clouds",
        "雪" to "snow",
        "下雨" to "rain",
        "雨" to "rain",
        "城市" to "city",
        "街道" to "street",
        "建筑" to "building",
        "高楼" to "skyscraper",
        "城堡" to "castle",
        "房子" to "house",
        "房间" to "room",
        "室内" to "indoors",
        "户外" to "outdoors",
        "公园" to "park",
        "花园" to "garden",
        "寺庙" to "temple",
        "桥" to "bridge",
        "宇宙" to "space, universe",
        "星球" to "planet",
        "飞船" to "spaceship",

        // objects / misc
        "汽车" to "car",
        "跑车" to "sports car",
        "摩托车" to "motorcycle",
        "剑" to "sword",
        "刀" to "blade",
        "枪" to "gun",
        "魔法" to "magic",
        "发光" to "glowing",
        "水晶" to "crystal",
        "灯光" to "lights",
        "霓虹灯" to "neon lights",
        "气球" to "balloon",
        "食物" to "food",
        "蛋糕" to "cake",
        "咖啡" to "coffee",
        "书" to "book",

        // colors
        "红色" to "red",
        "蓝色" to "blue",
        "绿色" to "green",
        "黄色" to "yellow",
        "白色" to "white",
        "黑色" to "black",
        "粉色" to "pink",
        "紫色" to "purple",
        "金色" to "golden",
        "银色" to "silver",

        // common verbs / adjectives
        "站在" to "standing",
        "坐在" to "sitting",
        "奔跑" to "running",
        "飞" to "flying",
        "拿着" to "holding",
        "拿着的" to "holding",
        "背景" to "background",
        "一个" to "a",
        "一只" to "a",
        "的" to " ",
        "和" to "and",
        "在" to " ",
        "与" to "and",
    )

    private val CJK = Regex("[\\u4e00-\\u9fff]")
    private val SPLIT = Regex("[\\s，,。.；;、！!？?（）()【】\\[\\]\"'“”‘’/\\\\]+")
    private val QUALITY = "masterpiece, best quality"
    private val NEG_HINT = "lowres, bad anatomy, bad hands, text, error, missing fingers, cropped, worst quality, low quality, jpeg artifacts, watermark, signature"

    fun hasChinese(s: String?): Boolean = s != null && CJK.containsMatchIn(s)

    /** Translate a Chinese prompt to an English tag prompt; pass English through. */
    fun translatePrompt(input: String?): String {
        if (input.isNullOrBlank()) return ""
        if (!hasChinese(input)) return input.trim()

        var work: String = input
        // Replace longest phrases first so e.g. 最高画质 wins over 高质量/画质.
        for ((zh, en) in DICT) {
            if (work.contains(zh)) work = work.replace(zh, " $en ")
        }
        // Tokenize, drop fragments that still contain CJK (unmapped), keep the rest.
        val tags = work.split(SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !hasChinese(it) }
            .distinct()
        val joined = tags.joinToString(", ")
        val body = if (joined.isBlank()) QUALITY else "$joined, $QUALITY"
        return body
    }

    /** A Chinese-friendly universal negative prompt (SD1.5 understands English). */
    fun defaultNegative(): String = NEG_HINT
}
