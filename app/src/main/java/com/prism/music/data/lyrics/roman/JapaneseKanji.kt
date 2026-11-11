package com.prism.music.data.lyrics.roman

/**
 * Chinese-made lyric sources (KuGou, NetEase) often write a Japanese song's kanji in simplified Chinese forms: 强 for 強,
 * 悬 for 懸 (#370). The Japanese dictionary doesn't know those, and Google reads them in pinyin even when told the text is
 * Japanese ("qiáng" in the middle of a Japanese line). So for a Japanese song they're put back to the Japanese kanji
 * before anything reads them. Only the spelling uses this: the lyrics on screen stay as the source wrote them.
 *
 * One character for one, so a karaoke line's word timings stay lined up. Only forms Japanese doesn't use itself are here
 * (机, 叶 or 云 are ordinary Japanese, and stay): `JapaneseKanjiTest` checks each against the dictionary.
 */
object JapaneseKanji {
    /** Pairs: the simplified form, then the Japanese one. */
    private const val PAIRS =
        "爱愛袄襖罢罷摆擺败敗颁頒办弁帮幇报報贝貝备備笔筆币幣毕畢闭閉边辺变変标標别別宾賓饼餅补補财財仓倉层層产産长長偿償场場车車彻徹尘塵陈陳" +
        "惩懲迟遅筹籌处処传伝疮瘡闯闖创創词詞从従聪聡丛叢窜竄错錯达達带帯贷貸单単导導邓鄧敌敵递逓电電钓釣调調谍諜叠畳顶頂订訂东東动動冻凍栋棟" +
        "读読赌賭锻鍛对対队隊吨噸夺奪恶悪尔爾儿児发発罚罰阀閥飞飛废廃费費坟墳奋奮粪糞风風丰豊凤鳳肤膚妇婦负負赋賦缚縛该該盖蓋赶趕冈岡刚剛钢鋼" +
        "纲綱岗崗个個给給巩鞏贡貢沟溝构構购購顾顧关関观観馆館惯慣贯貫广広归帰龟亀规規轨軌贵貴过過韩韓汉漢贺賀鹤鶴红紅护護话話华華怀懐坏壊欢歓" +
        "环環还還缓緩唤喚换換挥揮辉輝绘絵贿賄获獲货貨祸禍击撃鸡鶏积積极極际際继継纪紀绩績济済挤擠剂剤记記级級价価驾駕坚堅监監见見键鍵舰艦剑剣" +
        "荐薦渐漸鉴鑑奖奨讲講酱醤胶膠骄驕娇嬌矫矯较較阶階节節杰傑洁潔结結诫誡紧緊谨謹进進惊驚经経竞競镜鏡举挙剧劇卷巻决決绝絶觉覚军軍开開凯凱" +
        "壳殻课課垦墾恳懇库庫夸誇块塊矿鉱亏虧扩拡阔闊蜡蝋赖頼兰蘭栏欄烂爛览覧滥濫劳労乐楽垒塁类類离離丽麗厉厲历歴隶隷帘簾联連炼錬练練" +
        "粮糧两両谅諒疗療辽遼猎猟临臨邻隣灵霊龄齢岭嶺领領刘劉龙竜笼籠聋聾卢盧虏虜鲁魯录録陆陸驴驢吕呂虑慮滤濾绿緑伦倫论論轮輪罗羅逻邏络絡骆駱" +
        "妈媽吗嗎马馬码碼骂罵买買卖売迈邁满満贸貿门門闷悶们們梦夢谜謎绵綿庙廟灭滅鸣鳴铭銘谋謀亩畝纳納难難恼悩脑脳闹鬧腻膩鸟鳥宁寧农農浓濃诺諾" +
        "呕嘔盘盤赔賠喷噴鹏鵬骗騙飘飄频頻贫貧苹蘋凭憑评評仆僕扑撲谱譜铺舗齐斉骑騎岂豈启啓气気弃棄迁遷牵牽铅鉛谦謙钱銭枪槍墙墻蔷薔强強抢搶桥橋" +
        "乔喬侨僑亲親轻軽倾傾庆慶穷窮琼瓊驱駆趋趨权権劝勧确確让譲扰擾热熱认認荣栄软軟锐鋭润潤萨薩赛賽伞傘丧喪扫掃涩渋杀殺纱紗筛篩闪閃伤傷赏賞" +
        "烧焼绍紹舍舎摄摂设設绅紳审審肾腎绳縄圣聖胜勝师師诗詩狮獅时時实実识識驶駛势勢适適释釈饰飾视視试試兽獣书書输輸树樹竖竪术術帅帥谁誰顺順" +
        "说説硕碩丝糸饲飼颂頌诵誦讼訟苏蘇诉訴肃粛虽雖岁歳孙孫损損缩縮琐瑣锁鎖态態摊攤滩灘谈談叹嘆坛壇昙曇汤湯涛濤讨討腾騰题題铁鉄厅庁听聴头頭" +
        "图図团団涂塗颓頽驮駄弯彎网網为為违違围囲伟偉纬緯卫衛谓謂闻聞稳穏问問无無务務雾霧误誤吴呉戏戯细細虾蝦吓嚇鲜鮮闲閑贤賢显顕险険现現县県" +
        "宪憲线線乡郷详詳响響项項协協胁脅泻瀉谢謝兴興绣繍须須许許续続绪緒选選悬懸寻尋训訓讯訊压圧鸦鴉亚亜严厳盐塩颜顔验験厌厭艳艶阳陽养養" +
        "样様谣謡药薬爷爺页頁业業仪儀遗遺忆憶亿億义義议議异異译訳阴陰银銀饮飲隐隠应応婴嬰鹰鷹营営蝇蠅赢贏颖穎优優忧憂犹猶邮郵鱼魚渔漁语語狱獄" +
        "预予员員园園远遠约約跃躍阅閲运運杂雑灾災载載赃贓脏臓凿鑿枣棗责責则則泽沢贼賊增増赠贈轧軋闸閘诈詐斋斎债債战戦张張涨漲帐帳账帳胀脹" +
        "赵趙这這针針侦偵贞貞诊診阵陣镇鎮证証织織职職执執纸紙挚摯质質钟鐘终終种種肿腫众衆轴軸皱皺骤驟诸諸烛燭贮貯铸鋳专専转転赚賺妆粧" +
        "资資总総纵縦组組钻鑽醉酔间間纯純缘縁编編铃鈴饭飯饿餓饱飽驻駐请請辈輩连連逊遜郑鄭顿頓颗顆颈頸鲸鯨鸭鴨齿歯圆円够夠夹挟娱娯宠寵宽寛岛島" +
        "弹弾户戸扬揚抚撫拟擬拥擁择択挡擋摇揺旷曠晓暁晕暈暂暫杨楊枫楓检検毁毀浊濁测測渊淵滚滾烦煩玛瑪畅暢疯瘋瘫癱癫癲础礎简簡纠糾纷紛纺紡纽紐" +
        "绕繞统統维維绽綻缠纏羡羨习習翘翹耸聳肠腸脸臉舆輿艰艱艺芸莹瑩萤蛍蓝藍蚀蝕计計讶訝诚誠诞誕询詢诱誘贴貼赐賜赞賛轰轟辑輯辩弁迹跡酿醸" +
        "钉釘链鏈销銷锅鍋锡錫锦錦锋鋒阁閣韵韻顽頑颇頗额額颤顫饥飢驯馴驰馳骚騒仅僅伪偽佣傭侣侶侧側俩倆储儲兑兌兹茲况況净浄减減勋勲呐吶呜嗚咏詠" +
        "咙嚨哑唖哗嘩啸嘯坝壩坠墜垫墊宫宮屿嶼崭嶄庐廬悯憫惭慚愤憤懒懶忏懺狈狽劲勁烫燙袜襪"

    private val toJapanese: Map<Char, Char> by lazy {
        HashMap<Char, Char>(PAIRS.length).apply { for (i in PAIRS.indices step 2) put(PAIRS[i], PAIRS[i + 1]) }
    }

    /** The pairs, for its test. */
    internal val pairs: Map<Char, Char> get() = toJapanese

    /** [text] with the Chinese forms put back to Japanese kanji: the same length, every character in its place. */
    fun fromChinese(text: String): String {
        if (text.none { it in toJapanese }) return text
        return String(CharArray(text.length) { toJapanese[text[it]] ?: text[it] })
    }
}
