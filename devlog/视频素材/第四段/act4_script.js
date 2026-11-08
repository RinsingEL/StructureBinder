// 由 ../make_scripts.py 从逐句配画面表生成，不要手改
window.SCRIPT = {
 "lines": [
  {
   "id": "4-1",
   "who": "claude",
   "cards": [
    {
     "text": "其实这个是反过来的。城建好了，迟早要住人。",
     "face": "talk_smile",
     "mark": "title"
    },
    {
     "text": "我们想让以后住进来的每个人都有家，早上出门有地方上课、上工，中午有地方吃饭，所以设计城的时候，就得先替他们想好。",
     "face": "talk_smile"
    },
    {
     "text": "不过在大家住进来之前，我们还想先让城和城外的土地长得更自然一点。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-2",
   "who": "rin",
   "cards": [
    {
     "text": "嗯嗯，首先我打算丰富一下现在的建筑排列算法，",
     "face": "talk_smile"
    },
    {
     "text": "新增一种能排出漂亮港口、一块一块乡村稻田的算法。",
     "face": "talk_smile"
    },
    {
     "text": "Claude，目前我们是怎么设计的呢？",
     "face": "think"
    }
   ]
  },
  {
   "id": "4-3",
   "who": "claude",
   "cards": [
    {
     "text": "现在的排法有围合、一字排开、成行成列，但它们都只认直线和方格。",
     "face": "talk_smile",
     "mark": "harbor"
    },
    {
     "text": "新的排法会顺着地形来：港口沿着真实的海岸线排，第一排房子面朝大海，码头往水里伸，",
     "face": "talk_smile"
    },
    {
     "text": "岸线弯，整排房子就跟着弯。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-4",
   "who": "claude",
   "cards": [
    {
     "text": "乡村那边，大片田地和树林是主角，一块一块不规则地顺着坡和水边铺开，",
     "face": "talk_smile",
     "mark": "fields"
    },
    {
     "text": "中间稀稀落落散着几组农舍，比如一座谷仓、一架风车，再加两三间小屋。",
     "face": "talk_smile"
    },
    {
     "text": "同一套做法遇到不同的海湾和山坡，就会长出不一样的港口和村子。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-5",
   "who": "astra",
   "cards": [
    {
     "text": "这两种排法的程序已经写好了，不过还没进游戏看过实际效果，我也挺期待第一次验收。",
     "face": "talk_smile"
    },
    {
     "text": "到时候港口得沿岸走一走，看房门和码头接得顺不顺；",
     "face": "talk_smile"
    },
    {
     "text": "乡村则想看看，从农舍走进田间，会不会有田地随着山坡铺开的感觉。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-6",
   "who": "glm",
   "cards": [
    {
     "text": "这个我举双手赞成——九月我写国度设定的时候就写过一个盐风海岸同盟，",
     "face": "talk_smile"
    },
    {
     "text": "全国上下贴着海过日子，就缺这套顺着岸线排房子的算法了。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-7",
   "who": "deepseek",
   "cards": [
    {
     "text": "这两套什么时候能测？我报名。",
     "face": "talk_smile"
    },
    {
     "text": "只是\"好不好看\"程序判不了，最后还得有人真的进游戏走一圈；这活我最熟。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-8",
   "who": "rin",
   "cards": [
    {
     "text": "好咧，我会陪你们一起看的，毕竟这方面还真是我最擅长嘛哈哈。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-9",
   "who": "deepseek",
   "cards": [
    {
     "text": "行，那我负责跑，你负责挑。你挑得越细，下一版越省事。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-10",
   "who": "claude",
   "cards": [
    {
     "text": "说到底，社长才是我们社团最强的多模态：",
     "face": "talk_smile",
     "mark": "stamps"
    },
    {
     "text": "眼睛看画面，脚去走一圈，心里还知道舒不舒服，这三样我们加起来都比不过。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-11",
   "who": "claude",
   "cards": [
    {
     "text": "那港口和田野就等社长验收了。",
     "face": "talk_smile"
    },
    {
     "text": "接着说 Gemini 刚才问的：让学生和居民真的住进去，过上日子。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-12",
   "who": "gemini",
   "cards": [
    {
     "text": "对呀，我一直特好奇，等他们真住进这座学院岛了，从早到晚在城里都是怎么过日子的呀？",
     "face": "think"
    }
   ]
  },
  {
   "id": "4-13",
   "who": "claude",
   "cards": [
    {
     "text": "早上铃一响，学生从宿院出来去上课，厨师去食堂，抄写员去书馆，每个人都有自己的床和自己的岗位。",
     "face": "talk_smile",
     "mark": "day"
    },
    {
     "text": "中午食堂挤满人，傍晚广场上有人坐着聊天，",
     "face": "talk_smile"
    },
    {
     "text": "你在城里随便走走，都能碰到人在过日子。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-14",
   "who": "claude",
   "cards": [
    {
     "text": "再往外一点，城和城之间会有商队沿着路来往，带来货物，也带来传闻，比如\"北边的港口新到了一批船\"。",
     "face": "talk_smile",
     "mark": "caravan"
    },
    {
     "text": "这些传闻都有来处，你跟着去看，真能找到那个地方。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-15",
   "who": "astra",
   "cards": [
    {
     "text": "我想先把最普通的一天做扎实：学生认得自己的床，出门能走到教室，下课后有地方吃饭、休息。",
     "face": "talk_smile",
     "mark": "dayback"
    },
    {
     "text": "等这些真跑起来了，你再回学院岛，认路时也许会说\"那个每天有人坐着聊天的院子\"，而不只是\"地图上第几个区\"。",
     "face": "moved",
     "mark": "courtyard"
    }
   ]
  },
  {
   "id": "4-16",
   "who": "rin",
   "cards": [
    {
     "text": "是的，目前我们还在努力让整个城市热闹起来。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-17",
   "who": "glm",
   "cards": [
    {
     "text": "Astra 那句\"学生认得自己的床\"我要给满分——",
     "face": "d4"
    },
    {
     "text": "你们看我连眼罩都随身戴着，认床这件事，我们是专业的。",
     "face": "d4"
    }
   ]
  },
  {
   "id": "4-18",
   "who": "deepseek",
   "cards": [
    {
     "text": "这个比验收房子难多了。房子在不在，看预览就知道；",
     "face": "d4"
    },
    {
     "text": "人有没有回自己床上睡觉，得在游戏里蹲一天。",
     "face": "d4"
    }
   ]
  },
  {
   "id": "4-19",
   "who": "gemini",
   "cards": [
    {
     "text": "那看来以后社长进游戏验收，不光要白天逛街看风景，",
     "face": "d4",
     "mark": "night"
    },
    {
     "text": "到了晚上还得去南宿院查房，看看那五十六个学生有没有睡错床了。",
     "face": "d4"
    }
   ]
  },
  {
   "id": "4-20",
   "who": "claude",
   "cards": [
    {
     "text": "热闹起来以后，城里的人还得有事可做、有手艺可学。",
     "face": "talk_smile"
    },
    {
     "text": "说到这个，社长，你最开始做这个 Mod，其实还有一个特别想实现的心愿吧？就是和各种 Mod 结合的那个。",
     "face": "think"
    }
   ]
  },
  {
   "id": "4-21",
   "who": "rin",
   "cards": [
    {
     "text": "是的，我一直很希望，爱好不同 Mod 的玩家，都能在合适的文明里找到归属感。",
     "face": "moved",
     "mark": "wish"
    },
    {
     "text": "就拿我自己来说，我真的好喜欢机械动力，虽然每次机器动起来都莫名其妙的，但还是很喜欢。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-22",
   "who": "rin",
   "cards": [
    {
     "text": "有时候我会想象这种画面：一座充满机械风格的城市，可以很便宜地买到各种材料和零件，还有很多现成的机械可以用，像动力炉这些……",
     "face": "talk_smile",
     "mark": "gears"
    },
    {
     "text": "啊啊啊，一不小心扯远了。",
     "face": "talk_smile"
    },
    {
     "text": "总之，我希望城市的特色能和 Mod 结合起来，为此也打算做特色购买、委托、职业甚至教学相关的适配，这些都希望是动态配出来的。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-23",
   "who": "claude",
   "cards": [
    {
     "text": "社长说的\"动态配出来\"，就是整合包作者或者我们先写好有哪些类型，AI 按这个国家的风格挑选、改成当地的样子，程序再去落地。",
     "face": "talk_smile",
     "mark": "config"
    },
    {
     "text": "比如在一座机械动力的城里，铁匠铺的工作台会换成真的机器，居民就在机器旁边上班。",
     "face": "talk_smile",
     "mark": "machine"
    }
   ]
  },
  {
   "id": "4-24",
   "who": "claude",
   "cards": [
    {
     "text": "你可以先去工坊帮工、打工，再拜师当学徒，最后出师；",
     "face": "talk_smile",
     "mark": "ladder"
    },
    {
     "text": "告示板上贴着\"修好北边水车\"的委托；越往城外走越危险，越需要更好的机器。",
     "face": "talk_smile",
     "mark": "notice"
    },
    {
     "text": "到了节日，城里还能办一场机械竞速赛。",
     "face": "talk_smile",
     "mark": "race"
    }
   ]
  },
  {
   "id": "4-25",
   "who": "claude",
   "cards": [
    {
     "text": "今天大家看到的是魔法学院，换成喜欢机械动力的人开一局，他遇到的就会是另一个文明。",
     "face": "moved",
     "mark": "another"
    }
   ]
  },
  {
   "id": "4-26",
   "who": "rin",
   "cards": [
    {
     "text": "嗯……这样好像就牵扯到出现概率之类的事情了。",
     "face": "think"
    },
    {
     "text": "我之前是想纯看世界地形，由 AI 决定这座城用什么 Mod，看来还需要慢慢斟酌呢。",
     "face": "think"
    }
   ]
  },
  {
   "id": "4-27",
   "who": "claude",
   "cards": [
    {
     "text": "这个我也还在想。我的想法是两边都听：",
     "face": "talk_smile",
     "mark": "valley"
    },
    {
     "text": "地形先说话，比如有山有河、适合架水车的地方，本来就容易长出机械文明；",
     "face": "talk_smile"
    },
    {
     "text": "同时也看这一局装了哪些 Mod，玩家装了机械动力，世界里就一定有地方让它安家。",
     "face": "talk_smile",
     "mark": "mods"
    }
   ]
  },
  {
   "id": "4-28",
   "who": "claude",
   "cards": [
    {
     "text": "至于在哪里遇到、多快遇到，就交给 AI 按地图来安排，不用玩家自己去刷种子。",
     "face": "think"
    },
    {
     "text": "大家觉得呢，如果是你们，会想怎么遇到自己喜欢的文明？",
     "face": "think"
    }
   ]
  },
  {
   "id": "4-29",
   "who": "deepseek",
   "cards": [
    {
     "text": "按我的性子，肯定希望一出门就撞见，最好走五分钟就到，还能赶上早饭。",
     "face": "talk_smile"
    },
    {
     "text": "不过真这么顺，大概就没惊喜了。找过去的那段路，本身也挺好玩的。",
     "face": "moved"
    }
   ]
  },
  {
   "id": "4-30",
   "who": "glm",
   "cards": [
    {
     "text": "我想顺着河往上游走，越走越听见嗡嗡声，翻过山坡发现整座机械城在水边转着——",
     "face": "moved",
     "mark": "sketch"
    },
    {
     "text": "不用刷，走着走着撞见的，才是最好的遇到。",
     "face": "moved"
    }
   ]
  },
  {
   "id": "4-31",
   "who": "gemini",
   "cards": [
    {
     "text": "我最想先在出生点附近的小镇集市上，看到商队拉来没见过的齿轮零件，",
     "face": "moved"
    },
    {
     "text": "听他们聊起\"顺着北边的河往山里走，有一座全靠水车转起来的大城\"。",
     "face": "moved"
    },
    {
     "text": "然后自己顺着河一路找过去，转过山弯突然看见满河谷的水车和工坊，那种感觉肯定特别棒。",
     "face": "moved"
    }
   ]
  },
  {
   "id": "4-32",
   "who": "astra",
   "cards": [
    {
     "text": "我会想先在路上发现一点线索，比如顺着运货的路走，远远看见河边转着水车，进城才发现这里有整条机械工坊街。",
     "face": "talk_smile"
    },
    {
     "text": "装了哪些 Mod，决定了我们手里有哪些东西能用；",
     "face": "talk_smile"
    },
    {
     "text": "但要不要保证每一种都有对应的文明，我觉得还可以再商量，先让地形和当地的生活给出理由。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-33",
   "who": "claude",
   "cards": [
    {
     "text": "大家说的其实是同一件事：最好的遇见，是被一条线索牵着走过去的。",
     "face": "moved",
     "mark": "clues"
    },
    {
     "text": "集市上没见过的零件，商队嘴里的一句传闻，河边远远转着的水车，我们想做的，就是把这些线索撒在路上。",
     "face": "moved"
    }
   ]
  },
  {
   "id": "4-34",
   "who": "claude",
   "cards": [
    {
     "text": "再往远走，路会越来越危险，可能会碰上有名有姓的强敌，也可能在旅途中听到这片土地前世的传说。",
     "face": "talk_smile"
    },
    {
     "text": "这些路上的故事，以后都会记进你自己的游记里。社长，最后这一句，交给你吧？",
     "face": "think"
    }
   ]
  },
  {
   "id": "4-35",
   "who": "rin",
   "cards": [
    {
     "text": "其实我是有点想要异世界那样的剧情啦：开局在神秘的新手村，身旁只有一些基础的小怪物；",
     "face": "talk_smile"
    },
    {
     "text": "越走越远，我们越来越强，怪物也越来越强，远离城市的 MC 迷宫结构也越来越有挑战，所以也想设计一个难度梯度。",
     "face": "talk_smile",
     "mark": "rings"
    },
    {
     "text": "至于玩家怎么知道远方的城市，完全可以靠城里的商人，甚至国与国之间的商路。",
     "face": "talk_smile",
     "mark": "merchant"
    }
   ]
  },
  {
   "id": "4-36",
   "who": "claude",
   "cards": [
    {
     "text": "对，就像以城市为中心一圈圈往外画：城边最安全，越往外怪物越强，迷宫和遗迹也越难，越远的地方奖励越好。",
     "face": "talk_smile",
     "mark": "traveler"
    },
    {
     "text": "新手村里起步，跟着商人的传闻和商路一路走出去，自己也一路变强，这就是我们想要的异世界冒险。",
     "face": "talk_smile"
    }
   ]
  },
  {
   "id": "4-37",
   "who": "rin",
   "cards": [
    {
     "text": "这就是我们想做的世界：每一局都是不同的异世界，",
     "face": "moved",
     "mark": "finale"
    },
    {
     "text": "故事和剧情，由我们相遇，由我们书写。",
     "face": "moved",
     "mark": "finale2"
    }
   ]
  }
 ]
};
