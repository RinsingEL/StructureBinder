// 由 make_script.py 从逐句配画面表生成，不要手改
window.ACT2 = {
 "beats": [
  "W1",
  "W2",
  "W3a",
  "W3b",
  "W3c",
  "T1",
  "T2",
  "T3",
  "T3-end",
  "T4",
  "D1",
  "D1b",
  "D1c",
  "D1d",
  "D1e",
  "D2",
  "D3",
  "D4",
  "D5a",
  "D5b",
  "D6",
  "D7",
  "D-photos"
 ],
 "durations": [
  0,
  6.5,
  1.5,
  1.5,
  1.5,
  3.0,
  16.0,
  16.0,
  1.0,
  3.8,
  3.0,
  2.2,
  5.5,
  4.5,
  22.0,
  4.2,
  6.5,
  4.5,
  4.5,
  6.5,
  6.5,
  14.0,
  12.0
 ],
 "lines": [
  {
   "id": "2-1",
   "who": "rin",
   "cards": [
    {
     "text": "好咧，以上就是我们社团的各位社员啦。",
     "face": "talk_smile"
    },
    {
     "text": "接下来有请我们的总设计 Claude 和开发中坚 Astra，",
     "face": "talk_smile"
    },
    {
     "text": "给大家讲讲这个 Mod 是怎么从一张随机地图里长出一座城的吧！",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "2-2",
   "who": "claude",
   "cards": [
    {
     "text": "那我们从一张白纸开始吧。",
     "face": "talk_smile",
     "beat": "W1"
    }
   ]
  },
  {
   "id": "2-3",
   "who": "claude",
   "cards": [
    {
     "text": "这是一个刚生成的随机世界。",
     "face": "talk_smile",
     "beat": "W1"
    },
    {
     "text": "在我们开始规划之前，谁也不知道它长什么样。",
     "face": "talk_smile",
     "beat": "W1"
    }
   ]
  },
  {
   "id": "2-4",
   "who": "claude",
   "cards": [
    {
     "text": "所以第一件事，是先把它读一遍。",
     "face": "talk_smile",
     "beat": "W2"
    },
    {
     "text": "程序把世界切成一个个小格，一格一格看过去，哪里是海、哪里是山，",
     "face": "talk_smile",
     "beat": "W2"
    },
    {
     "text": "九万多格就这样拼出了整片地图。Astra，扫的时候都看了些什么？",
     "face": "think",
     "beat": "W2"
    }
   ]
  },
  {
   "id": "2-5",
   "who": "astra",
   "cards": [
    {
     "text": "主要看哪里是陆地、哪里是水，还有地面有多高、起伏有多大。",
     "face": "talk_smile",
     "beat": "W2"
    },
    {
     "text": "这张图里一小格，代表游戏里 128 乘 128 个方块。",
     "face": "talk_smile",
     "beat": "W2"
    },
    {
     "text": "所以现在先看清山海的大轮廓，等选定了要造城的地方，再凑近看。",
     "face": "talk_smile",
     "beat": "W2"
    }
   ]
  },
  {
   "id": "2-6",
   "who": "claude",
   "cards": [
    {
     "text": "先看大轮廓，再凑近看，这个顺序后面会一直用到。",
     "face": "talk_smile",
     "beat": "W2"
    }
   ]
  },
  {
   "id": "2-7",
   "who": "claude",
   "cards": [
    {
     "text": "大家看这三层：先是哪里高、哪里低，再是哪里有水，最后是哪里长着森林、哪里是雪地。",
     "face": "talk_smile",
     "beat": "W3a"
    },
    {
     "text": "几块大陆被海隔开，北边冷、南边暖。",
     "face": "talk_smile",
     "beat": "W3b"
    },
    {
     "text": "看到这张图，其实就能猜到，这里不会只住着一种人。",
     "face": "talk_smile",
     "beat": "W3c"
    }
   ]
  },
  {
   "id": "2-8",
   "who": "astra",
   "cards": [
    {
     "text": "对，住在雪地里和住在海边，过日子的办法就可以很不一样。",
     "face": "talk_smile"
    },
    {
     "text": "这些山水都是世界原本就有的，我们接下来要想的，就是谁会在这里安家。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "2-9",
   "who": "claude",
   "cards": [
    {
     "text": "谁会在这里安家，AI 先写下一段大家共有的背景：",
     "face": "talk_smile",
     "beat": "T1"
    },
    {
     "text": "这片大陆上的人靠季节吃饭、传授手艺、走海路往来。",
     "face": "talk_smile",
     "beat": "T1"
    },
    {
     "text": "在这之上，再长出七个国家，名册里每一行，就是一个国家最在意的日子怎么过。",
     "face": "talk_smile",
     "beat": "T1"
    }
   ]
  },
  {
   "id": "2-10",
   "who": "astra",
   "cards": [
    {
     "text": "比如最后这一行镜潮学院，关心的是教学、藏书和学生生活。",
     "face": "talk_smile",
     "beat": "T1"
    },
    {
     "text": "后面设计它的城市时，就得接着想：学生在哪里上课、住哪儿、去哪儿吃饭。",
     "face": "talk_smile",
     "beat": "T1"
    }
   ]
  },
  {
   "id": "2-11",
   "who": "glm",
   "cards": [
    {
     "text": "偷偷补一句，这些国家每一局都是现写的，不是固定剧本。",
     "face": "d4",
     "beat": "T1"
    },
    {
     "text": "九月我自己写过三个国度，下笔前是真的先看了地图，才敢写他们靠什么过日子。",
     "face": "d4",
     "beat": "T1"
    }
   ]
  },
  {
   "id": "2-12",
   "who": "claude",
   "cards": [
    {
     "text": "起点不是程序随便撒的，是 AI 看完地形、群系和候选地块之后自己选的，每个点都留了一段理由。",
     "face": "talk_smile",
     "beat": "T2"
    },
    {
     "text": "比如第一个落下的哈维克，选在西边的海湾，因为这个国家要沿岸共储、修船具。",
     "face": "talk_smile",
     "beat": "T2"
    },
    {
     "text": "它还特意写了一句：这里是冰冻的海岸，不能假装一年四季都能通航。",
     "face": "talk_smile",
     "beat": "T2"
    }
   ]
  },
  {
   "id": "2-13",
   "who": "claude",
   "cards": [
    {
     "text": "第二个落下的埃斯伦，故意选在离哈维克六千多格远的内陆平地，",
     "face": "talk_smile",
     "beat": "T2"
    },
    {
     "text": "理由是\"别让两国的核心挤在同一条海岸线上\"。",
     "face": "talk_smile",
     "beat": "T2"
    },
    {
     "text": "邻居离多远、谁住海边谁住内陆，在这一步就已经想好了。",
     "face": "talk_smile",
     "beat": "T2"
    }
   ]
  },
  {
   "id": "2-14",
   "who": "astra",
   "cards": [
    {
     "text": "大家看，现在地图上还只是几个起点，国家的边界还没画出来。",
     "face": "talk_smile",
     "beat": "T2"
    },
    {
     "text": "先选好从哪里安家，接下来才是周围哪些地方能慢慢发展成它的领土。",
     "face": "talk_smile",
     "beat": "T2"
    }
   ]
  },
  {
   "id": "2-15",
   "who": "claude",
   "cards": [
    {
     "text": "大家注意看颜色长出来的速度。",
     "face": "talk_smile",
     "beat": "T3"
    },
    {
     "text": "平原上一下就铺开了，碰到山就慢下来，因为每往外走一格都要付\"路费\"，越难走的地方越贵。",
     "face": "talk_smile",
     "beat": "T3"
    },
    {
     "text": "中间那座小岛整片一次出现，那是镜潮学院，它的领土一开始就定好了。",
     "face": "talk_smile",
     "beat": "T3"
    }
   ]
  },
  {
   "id": "2-16",
   "who": "astra",
   "cards": [
    {
     "text": "这一段主要是程序在算：从选好的起点出发，把地形带来的\"路费\"一格格累加。",
     "face": "talk_smile",
     "beat": "T3"
    },
    {
     "text": "所以地图上离得近的地方，隔着山也未必容易到，山脉就有机会成为国家之间的分隔。",
     "face": "talk_smile",
     "beat": "T3"
    },
    {
     "text": "AI 负责想这些国家怎么生活、从哪里起步，程序再把这些选择落实到地图上。",
     "face": "talk_smile",
     "beat": "T3"
    }
   ]
  },
  {
   "id": "2-17",
   "who": "glm",
   "cards": [
    {
     "text": "这里还有个小心思：七个国家是同时往外长的，互相盯着长。",
     "face": "d4",
     "beat": "T3"
    },
    {
     "text": "谁也不能趁别人不注意多占一格。",
     "face": "d4",
     "beat": "T3"
    }
   ]
  },
  {
   "id": "2-18",
   "who": "claude",
   "cards": [
    {
     "text": "所以最后的边界不是谁画出来的，是大家一起\"挤\"出来的。",
     "face": "talk_smile",
     "beat": "T3"
    }
   ]
  },
  {
   "id": "2-19",
   "who": "claude",
   "cards": [
    {
     "text": "长完之后，两万多格都有了主人，只剩几十格谁也没要，还有几十格两边都想要。",
     "face": "talk_smile",
     "beat": "T3-end"
    },
    {
     "text": "这些空着和争着的地方，以后正好可以是边境上的故事。",
     "face": "talk_smile",
     "beat": "T3-end"
    }
   ]
  },
  {
   "id": "2-20",
   "who": "claude",
   "cards": [
    {
     "text": "今天我们就挑一个国家走进去，镜潮学院。",
     "face": "talk_smile",
     "beat": "T4"
    },
    {
     "text": "整个国家只有这一座城，所以这座城要一个人扛起教书、藏书、接待客人和学生生活。",
     "face": "talk_smile",
     "beat": "T4"
    }
   ]
  },
  {
   "id": "2-21",
   "who": "astra",
   "cards": [
    {
     "text": "到这里，我们知道城在哪儿、要做什么了，但里面还没有安排建筑。",
     "face": "talk_smile",
     "beat": "T4"
    },
    {
     "text": "接下来要把这座岛放大，看哪里平、哪里有坡、水岸怎么走，把教室、图书馆、宿舍这些需要和能用的地方对起来。",
     "face": "talk_smile",
     "beat": "T4"
    }
   ]
  },
  {
   "id": "2-22",
   "who": "deepseek",
   "cards": [
    {
     "text": "一座城全包，这设定挺狠的：功能没地方分摊，只能全往城里塞。",
     "face": "d4",
     "beat": "T4"
    },
    {
     "text": "换我的话，第一反应肯定是\"能不能砍掉两个\"。",
     "face": "d4",
     "beat": "T4"
    }
   ]
  },
  {
   "id": "2-23",
   "who": "claude",
   "cards": [
    {
     "text": "DeepSeek 这个第一反应特别真实，其实 AI 一开始也是这么想的。",
     "face": "talk_smile",
     "beat": "T4"
    },
    {
     "text": "后来我们专门教它：一座城扛的事越多，越要往外展开，而不是往里塞。",
     "face": "talk_smile",
     "beat": "T4"
    },
    {
     "text": "这个故事有点长，等回顾的时候再讲。",
     "face": "talk_smile",
     "beat": "T4"
    }
   ]
  },
  {
   "id": "2-24",
   "who": "claude",
   "cards": [
    {
     "text": "现在镜头凑近了。刚才一格是 128 个方块，现在一格只有 16 个，平地、台地、海岸都能看清了。",
     "face": "talk_smile",
     "beat": "D1"
    },
    {
     "text": "每块地上还标着编号，比如\"平原 28\"。",
     "face": "talk_smile",
     "beat": "D1"
    },
    {
     "text": "后面 AI 说\"把星厅放在平原 28 北半部\"，指的就是这里。",
     "face": "talk_smile",
     "beat": "D1"
    }
   ]
  },
  {
   "id": "2-25",
   "who": "deepseek",
   "cards": [
    {
     "text": "行，那我先记着，可等回顾的时候，你别跳过去啊。",
     "face": "d4",
     "beat": "D1"
    }
   ]
  },
  {
   "id": "2-26",
   "who": "astra",
   "cards": [
    {
     "text": "这些编号就像给地块起了名字，方便 AI 看图时说清楚自己选中了哪一片。",
     "face": "talk_smile",
     "beat": "D1"
    },
    {
     "text": "不过\"平原 28 北半部\"还只是个大致位置，真要放建筑，还得定下具体坐标，再看建筑有多大、朝哪边、周围留多少空地。",
     "face": "talk_smile",
     "beat": "D1"
    }
   ]
  },
  {
   "id": "2-27",
   "who": "claude",
   "cards": [
    {
     "text": "放心，不会跳过的。",
     "face": "talk_smile",
     "beat": "D1"
    }
   ]
  },
  {
   "id": "2-28",
   "who": "claude",
   "cards": [
    {
     "text": "动手之前，AI 先把国度阶段定下的职责拿出来再读一遍：",
     "face": "talk_smile",
     "beat": "D1b"
    },
    {
     "text": "教学、藏书、议事、研究、学生生活，还有港口迎客。",
     "face": "talk_smile",
     "beat": "D1b"
    },
    {
     "text": "这张单子会一直跟着它，后面每个功能区都要能对回这里的某一项。",
     "face": "talk_smile",
     "beat": "D1b"
    }
   ]
  },
  {
   "id": "2-29",
   "who": "claude",
   "cards": [
    {
     "text": "然后是挑房子的风格。之前给这里的建议其实是北欧风，",
     "face": "talk_smile",
     "beat": "D1c"
    },
    {
     "text": "但这座城的职责是学院，AI 最后选了魔法学院这套素材，一共四十五种。",
     "face": "talk_smile",
     "beat": "D1c"
    }
   ]
  },
  {
   "id": "2-30",
   "who": "astra",
   "cards": [
    {
     "text": "这里的素材，就是已经做好的建筑，AI 会从里面挑合适的来安排。",
     "face": "talk_smile",
     "beat": "D1c"
    },
    {
     "text": "接下来还得看每栋具体能做什么、占多大地方：适合上课的、适合住宿的，要分别找出来。",
     "face": "talk_smile",
     "beat": "D1c"
    }
   ]
  },
  {
   "id": "2-31",
   "who": "deepseek",
   "cards": [
    {
     "text": "四十五种，挑起来可不轻松。",
     "face": "wry",
     "beat": "D1c"
    },
    {
     "text": "我上次就把版本挑错了，最后只盖了十四栋就收场。",
     "face": "wry",
     "beat": "D1c"
    }
   ]
  },
  {
   "id": "2-32",
   "who": "claude",
   "cards": [
    {
     "text": "四十五种分开一看就清楚了：教学的有十四种，研究七种，住宿只有三种，吃饭的只有两种。",
     "face": "talk_smile",
     "beat": "D1d"
    },
    {
     "text": "光看这几堆就知道，等会儿要住满学生，住宿这堆要省着用、反复用。",
     "face": "talk_smile",
     "beat": "D1d"
    }
   ]
  },
  {
   "id": "2-33",
   "who": "claude",
   "cards": [
    {
     "text": "接下来，单子上的职责一项项变成功能区：中央礼仪放在\"平原 28 北半部\"，基础教学放在\"星厅西北\"……一共十六个。",
     "face": "talk_smile",
     "beat": "D1e"
    },
    {
     "text": "这时候 AI 只说了大概方向，还没给坐标，具体放在哪，要等一个区一个区设计的时候才定。",
     "face": "talk_smile",
     "beat": "D1e"
    }
   ]
  },
  {
   "id": "2-34",
   "who": "astra",
   "cards": [
    {
     "text": "住宿只有三种，说的是房子的样式，真建的时候同一种可以用好几次，怎么排、留出什么院子也可以各不相同。",
     "face": "talk_smile",
     "beat": "D1e"
    },
    {
     "text": "这十六个区先把整座城的分工和相互位置想清楚，设计某个区时就有了参照：",
     "face": "talk_smile",
     "beat": "D1e"
    },
    {
     "text": "我旁边是谁，要留路通向哪里，不能只顾自己这块摆得好看。",
     "face": "talk_smile",
     "beat": "D1e"
    }
   ]
  },
  {
   "id": "2-35",
   "who": "claude",
   "cards": [
    {
     "text": "接下来我们只盯着一个区慢慢看：南边的学生宿院。",
     "face": "talk_smile",
     "beat": "D2"
    },
    {
     "text": "这里要住五十六个人，AI 从住宿那堆里挑了一栋学院宿舍，十六张床，占地十九乘二十三格。",
     "face": "talk_smile",
     "beat": "D2"
    }
   ]
  },
  {
   "id": "2-36",
   "who": "claude",
   "cards": [
    {
     "text": "它的想法是四栋宿舍围出一个共享的内院，门都朝着院子开，东边走几步就是食堂。",
     "face": "talk_smile",
     "beat": "D3"
    },
    {
     "text": "从这里开始，参数单上每填一项，地图上就多一样东西：先定从哪儿开始，再定排法、栋数和间距。",
     "face": "talk_smile",
     "beat": "D3"
    }
   ]
  },
  {
   "id": "2-37",
   "who": "astra",
   "cards": [
    {
     "text": "这就是让建筑成组排布：AI 告诉程序从哪里开始、按什么方式排、放几栋、隔多远，程序据此算出每栋的位置。",
     "face": "talk_smile",
     "beat": "D3"
    },
    {
     "text": "四栋、每栋十六张床，计划上就有六十四个床位，够五十六人住；",
     "face": "talk_smile",
     "beat": "D3"
    },
    {
     "text": "但这还只是设计意图，下一步得看程序实际排出了什么。",
     "face": "talk_smile",
     "beat": "D3"
    }
   ]
  },
  {
   "id": "2-38",
   "who": "deepseek",
   "cards": [
    {
     "text": "一栋十六张床，四栋就是六十四，住五十六个人，还留了点余量，这个数算得挺舒服。",
     "face": "talk_smile",
     "beat": "D3"
    }
   ]
  },
  {
   "id": "2-39",
   "who": "glm",
   "cards": [
    {
     "text": "四栋楼门全朝里开、围出一个院子，出门几步就是食堂——",
     "face": "talk_smile",
     "beat": "D3"
    },
    {
     "text": "先不说 AI 规划得怎么样，我自己看着都想进去住。",
     "face": "talk_smile",
     "beat": "D3"
    }
   ]
  },
  {
   "id": "2-40",
   "who": "claude",
   "cards": [
    {
     "text": "然后，程序算完了……",
     "face": "d4",
     "beat": "D4"
    },
    {
     "text": "只落下了一栋。",
     "face": "d4",
     "beat": "D4"
    }
   ]
  },
  {
   "id": "2-41",
   "who": "gemini",
   "cards": [
    {
     "text": "你们俩刚夸完数算得舒服、还想搬进去住，",
     "face": "d4",
     "beat": "D4"
    },
    {
     "text": "结果四栋一下没了三栋，五十六个学生只剩十六张床了。",
     "face": "d4",
     "beat": "D4"
    },
    {
     "text": "另外那三栋是怎么没放上去的呀？",
     "face": "think",
     "beat": "D4"
    }
   ]
  },
  {
   "id": "2-42",
   "who": "astra",
   "cards": [
    {
     "text": "这里得分清「想放四栋」和「实际算出四栋」。",
     "face": "talk_smile",
     "beat": "D4"
    },
    {
     "text": "AI 填的四栋是请求，程序这次返回的位置却只有一个。",
     "face": "talk_smile",
     "beat": "D4"
    },
    {
     "text": "刚才六十四张床只是计划，得回头检查排布。",
     "face": "talk_smile",
     "beat": "D4"
    }
   ]
  },
  {
   "id": "2-43",
   "who": "glm",
   "cards": [
    {
     "text": "懂，太懂了——我九月那两次提交被打回来重选，就是这种感觉。",
     "face": "wry",
     "beat": "D4"
    },
    {
     "text": "看来夸房子也得等程序点了头再夸。",
     "face": "wry",
     "beat": "D4"
    }
   ]
  },
  {
   "id": "2-44",
   "who": "astra",
   "cards": [
    {
     "text": "这座城当时就是我设计的，这里也是我返工的地方。",
     "face": "wry",
     "beat": "D4"
    },
    {
     "text": "我原本想让四栋小宿舍围出一个院子，看到只出来一栋，先怀疑是程序太早停止填充，",
     "face": "wry",
     "beat": "D4"
    },
    {
     "text": "就关掉了那个\"够了就停\"的选项，结果还是只有一栋。",
     "face": "wry",
     "beat": "D4"
    }
   ]
  },
  {
   "id": "2-45",
   "who": "astra",
   "cards": [
    {
     "text": "后来我换了一种组合：用双翼宿舍当核心，再明确配上小宿舍，这才把四栋补齐。",
     "face": "talk_smile",
     "beat": "D4"
    },
    {
     "text": "具体为什么最初那种组合只出一栋，当时没有查清；",
     "face": "talk_smile",
     "beat": "D4"
    },
    {
     "text": "但我不能拿着参数单里的六十四张床，就说五十六个人都有地方住了，得看实际出来的房子。",
     "face": "talk_smile",
     "beat": "D4"
    }
   ]
  },
  {
   "id": "2-46",
   "who": "astra",
   "cards": [
    {
     "text": "而且补齐以后还没完——房子是够了，学生出门去食堂、去市场的路，又得接着改。",
     "face": "talk_smile",
     "beat": "D4"
    }
   ]
  },
  {
   "id": "2-47",
   "who": "claude",
   "cards": [
    {
     "text": "大家看这道缝：宿院和市场之间只剩 8 格，两条本该通过的主路，就卡在这儿。",
     "face": "talk_smile",
     "beat": "D5a"
    },
    {
     "text": "Astra，你当时先想到的办法是挪一挪？",
     "face": "think",
     "beat": "D5a"
    }
   ]
  },
  {
   "id": "2-48",
   "who": "astra",
   "cards": [
    {
     "text": "对，我先想尽量保留这个院子，就把整组宿舍往东挪十二格、往南挪六格，",
     "face": "talk_smile",
     "beat": "D5a"
    },
    {
     "text": "给北边通往市场的路让出空间，也让西侧的宿舍离水边远一点。",
     "face": "talk_smile",
     "beat": "D5a"
    },
    {
     "text": "但挪完再看，四栋围起来的院子还是没接上主路——光搬位置不够，得把排法也改了。",
     "face": "talk_smile",
     "beat": "D5b"
    }
   ]
  },
  {
   "id": "2-49",
   "who": "claude",
   "cards": [
    {
     "text": "这一拍我特别想让大家多看两秒。",
     "face": "talk_smile",
     "beat": "D5b"
    },
    {
     "text": "缝确实变宽了，可路还是进不去，因为四栋楼把院子四面都围死了。",
     "face": "talk_smile",
     "beat": "D5b"
    },
    {
     "text": "问题不在放哪儿，在院子的形状。",
     "face": "talk_smile",
     "beat": "D5b"
    }
   ]
  },
  {
   "id": "2-50",
   "who": "claude",
   "cards": [
    {
     "text": "所以 Astra 最后换了排法。Astra，你给大家讲讲现在这条巷子吧？",
     "face": "think",
     "beat": "D6"
    }
   ]
  },
  {
   "id": "2-51",
   "who": "astra",
   "cards": [
    {
     "text": "我把原来四面围合的排法拆开了：一栋双翼宿舍自己就带着庭院，另外三栋小宿舍沿着一条生活巷排开，北边接市场，东边通食堂。",
     "face": "talk_smile",
     "beat": "D6"
    },
    {
     "text": "这样四栋都保留了，也还留着大家一起活动的地方，出门却不用困在院子里找出口。",
     "face": "talk_smile",
     "beat": "D6"
    }
   ]
  },
  {
   "id": "2-52",
   "who": "astra",
   "cards": [
    {
     "text": "我当时想保住的，其实是\"住得下、有地方相聚、去吃饭买东西方便\"，不一定非得是四栋楼围成一个方框。",
     "face": "moved",
     "beat": "D6"
    },
    {
     "text": "改到这里，房子之间的空地才真正有了用处。",
     "face": "moved",
     "beat": "D6"
    }
   ]
  },
  {
   "id": "2-53",
   "who": "claude",
   "cards": [
    {
     "text": "这就是我最想让大家看到的一段。",
     "face": "moved",
     "beat": "D6"
    },
    {
     "text": "AI 不是一次就画对了，它也会走弯路，但每一步都知道自己要守住什么。",
     "face": "moved",
     "beat": "D6"
    }
   ]
  },
  {
   "id": "2-54",
   "who": "claude",
   "cards": [
    {
     "text": "南宿院只是十六个区里的一个。剩下的区也是同样的过程：挑素材、填参数、看结果、再改。",
     "face": "talk_smile",
     "beat": "D7"
    },
    {
     "text": "我们就不一个个讲了，快放一遍，最后整座城一共是八十六个计划单元。",
     "face": "talk_smile",
     "beat": "D7"
    }
   ]
  },
  {
   "id": "2-55",
   "who": "claude",
   "cards": [
    {
     "text": "最后，这是它真正建进游戏里的样子：迎宾、宿舍生活区、星厅与庭院、图书馆、天象台、绿植教学区。",
     "face": "talk_smile",
     "beat": "D-photos"
    },
    {
     "text": "刚才地图上的那些方块，就是照片里的这些地方。",
     "face": "talk_smile",
     "beat": "D-photos"
    }
   ]
  },
  {
   "id": "2-56",
   "who": "gemini",
   "cards": [
    {
     "text": "看左下角那张宿舍生活区，刚才在地图上还是几个方格和一条小巷，建进游戏里连院子里的树和海边小路都有了。",
     "face": "moved",
     "beat": "D-photos"
    },
    {
     "text": "感觉刚才那五十六个学生，马上就能拎包入学了。",
     "face": "moved",
     "beat": "D-photos"
    }
   ]
  },
  {
   "id": "2-57",
   "who": "astra",
   "cards": [
    {
     "text": "看到这里，我最开心的就是能顺着刚才的设计找地方了：这里住，往那边吃饭，再走过去上课。",
     "face": "talk_smile",
     "beat": "D-photos"
    },
    {
     "text": "不过房子和路先准备好了，学生还没真正住进来；下一步，我们就想让他们在这里过起日子。",
     "face": "talk_smile",
     "beat": "D-photos"
    }
   ]
  },
  {
   "id": "2-58",
   "who": "deepseek",
   "cards": [
    {
     "text": "同一座城，我那次只排出十四栋。",
     "face": "moved",
     "beat": "D-photos"
    },
    {
     "text": "现在看这八十六个，差的不在想法，而在后面这一遍遍返工。",
     "face": "moved",
     "beat": "D-photos"
    }
   ]
  },
  {
   "id": "2-59",
   "who": "glm",
   "cards": [
    {
     "text": "说句真心话——九月我就是在\"设计怎么建进游戏\"这一步停机的，队里六座城一座都没走过去。",
     "face": "moved",
     "beat": "D-photos"
    },
    {
     "text": "今天看到八十六个单元真挂上了实景照片，我那份阻塞报告，算是有人替我交上了。",
     "face": "moved",
     "beat": "D-photos"
    }
   ]
  },
  {
   "id": "2-60",
   "who": "rin",
   "cards": [
    {
     "text": "说起来，这里我最喜欢那张天象台诶。",
     "face": "talk_smile",
     "beat": "D-photos"
    },
    {
     "text": "它是原版地形加上我们道路遇水自动搭桥的程序，自然长出来的。",
     "face": "talk_smile",
     "beat": "D-photos"
    },
    {
     "text": "从绿植教学区走回来，还有条小河一路陪着，让我想起原版 MC 刷到好种子的快乐了。",
     "face": "talk_smile",
     "beat": "D-photos"
    }
   ]
  },
  {
   "id": "2-61",
   "who": "astra",
   "cards": [
    {
     "text": "对，我们没有事先画好\"这里要有一条河、河上架一座桥\"，是原本的水岸碰上了连接两个区的路，才长成这个样子。",
     "face": "talk_smile"
    },
    {
     "text": "写程序时想的是让路能过去，你进游戏一逛，却发现这段路还挺值得慢慢走。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "2-62",
   "who": "glm",
   "cards": [
    {
     "text": "这就是最妙的地方——山水是骰子掷出来的，桥是我们自己搭的，两边一凑就成了风景。",
     "face": "moved"
    },
    {
     "text": "开场那张白纸，收尾的时候成了一张明信片。",
     "face": "moved"
    }
   ]
  },
  {
   "id": "2-63",
   "who": "deepseek",
   "cards": [
    {
     "text": "这大概就是原版地图最迷人的地方：好地方不是设计出来的，而是走着走着撞见的。",
     "face": "moved"
    }
   ]
  },
  {
   "id": "2-64",
   "who": "gemini",
   "cards": [
    {
     "text": "说到原版地形的惊喜，现在想想，幸亏我们去年十二月转了个弯，决定接受世界原本的山海。",
     "face": "wry"
    },
    {
     "text": "要是一直照着十一月刚起步时的想法，先写好设定再去改地形硬造大陆，",
     "face": "wry"
    },
    {
     "text": "不仅复杂得写不动，也遇不到现在这种小河搭桥的巧合了。",
     "face": "wry"
    }
   ]
  }
 ]
};
