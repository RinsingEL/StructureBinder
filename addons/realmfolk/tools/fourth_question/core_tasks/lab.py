"""Executable, isolated task-rule experiment. Browser events simulate player actions;
they are never Minecraft interaction, spell, crafting, or worldgen evidence.
"""
import argparse
import copy
import hashlib
import json
import math
import re
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / 'results/academy'
OUTPUT = HERE / 'results'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    temporary.replace(path)


def require(valid, message):
    if not valid:
        raise ValueError(message)


def checked_ids(values, choices, name):
    require(isinstance(values, list) and values and len(values) == len(set(values)), '空或重复的' + name)
    require(set(values) <= set(choices), '未知' + name)


def compile_pack(author, intent, evidence, runtime, economy):
    require(author['schema'] == 'realmfolk.core-tasks.experiment.v1', '未知作者规则版本')
    require(intent['schema'] == 'realmfolk.task-bindings.experiment.v1', '未知 AI 绑定版本')
    require(runtime['sourceBlueprintHash'] == evidence['source']['blueprintHash'], '居民与 D4 不是同一版本')
    profiles = {p['semanticProfileId']: p for p in evidence['snapshot']['structureCatalog']['semanticProfiles']}
    buildings = {}
    for a in evidence['anchors']['anchors']:
        profile = profiles[a['templateId']]
        require(profile['reviewState'] == 'approved', '建筑用途未经批准')
        b = a['plannedFootprint']
        buildings[a['anchorId']] = {'id': a['anchorId'], 'asset': a['templateId'], 'group': a['placementGroupId'],
                                    'terms': profile['functionTerms'], 'bounds': b,
                                    'position': {'x': (b['minX'] + b['maxX']) // 2, 'z': (b['minZ'] + b['maxZ']) // 2}}
    require(len(buildings) == len(evidence['anchors']['anchors']), '建筑 ID 重复')
    for section in ('routes', 'rewards'):
        for rule in author[section].values():
            items = rule.get('items', {rule.get('item'): rule.get('count')})
            for item, count in items.items():
                require(item in economy['goods'] and type(count) is int and 1 <= count <= 64, '非法作者物品或数量')
    for loadout in author['playtest']['loadouts'].values():
        require(all(i in economy['goods'] and type(n) is int and n > 0 for i, n in loadout['items'].items()), '非法试验背包')
    for typ in author['types'].values():
        require(all(type(typ['reward'][k]) is int and 0 <= typ['reward'][k] <= 128 for k in ('coins', 'favor')), '非法作者回报')
    programs = []
    seen = set()
    for binding in intent['programs']:
        require(set(binding) == {'id','name','program','hostGroup','partnerGroup','siteFunction','theme','routes','rewardChoices','purpose','welcome','partnerLine'}, 'AI 配置字段缺失或未知')
        require(re.fullmatch(r'[a-z][a-z0-9_]{0,40}', binding['id']) and binding['id'] not in seen, '非法或重复任务方案 ID')
        seen.add(binding['id'])
        require(binding['program'] in author['programs'], '未知作者流程')
        rule = author['programs'][binding['program']]
        require(rule['stages'] == ['meet_community', 'professional_supply', 'shared_research'], '流程需要已实现的三个执行器')
        expected = {'meet_community':['talk_host','talk_partner'], 'professional_supply':['contribute_one_route'], 'shared_research':['field_record','return_to_host','choose_reward']}
        require(all(author['types'][k]['checks'] == v for k,v in expected.items()), '未实现的任务判定，不能仅改 JSON 获得新行为')
        require(binding['theme'] in rule['allowedThemes'], '此流程不允许该主题')
        theme = author['themes'][binding['theme']]
        require(binding['siteFunction'] in rule['allowedSiteTerms'] and binding['siteFunction'] in theme['siteTerms'], '主题与场地用途不匹配')
        checked_ids(binding['routes'], author['routes'], '贡献途径')
        checked_ids(binding['rewardChoices'], rule['rewardBundles'], '奖励包')
        for choice in binding['rewardChoices']:
            value = sum(economy['goods'][item]['price'] * n for item,n in author['rewards'][choice]['items'].items())
            require(value <= rule['rewardBudget'], '奖励超过作者预算')
        actors = []
        for role, terms in [('host', rule['allowedHostTerms']), ('partner', rule['allowedPartnerTerms'])]:
            candidates = [c for c in runtime['citizens'] if c['group'] == binding[role+'Group'] and 'trade' in c['capabilities']
                          and c['workplace'] in buildings and set(buildings[c['workplace']]['terms']) & set(terms)]
            require(candidates, '未找到具有实际岗位的' + role)
            npc = min(candidates, key=lambda c: c['id'])
            actors.append({'id': npc['id'], 'name': npc['name'], 'role': role, 'profession': npc['profession'], 'group': npc['group'], 'place': npc['workplace']})
        host, partner = actors
        require(host['id'] != partner['id'], '介绍人和合作者必须不同')
        origin = buildings[host['place']]['position']
        places = [b for b in buildings.values() if binding['siteFunction'] in b['terms']]
        places.sort(key=lambda b: (math.hypot(b['position']['x']-origin['x'], b['position']['z']-origin['z']), b['id']))
        require(places, '没有真实用途匹配的活动场所')
        site = places[0]
        distance = math.hypot(site['position']['x']-origin['x'], site['position']['z']-origin['z'])
        require(distance <= rule['maximumSiteDistance'], '活动场所超出作者距离范围')
        require(all(i in economy['goods'] for i in theme['serviceItems']), '服务含未知物品')
        stages = [{'type': tid, 'title': title, 'reward': author['types'][tid]['reward']} for tid,title in zip(rule['stages'], ['认识他们的生活', '用你的专长帮忙', theme['name']])]
        programs.append({**binding, 'actors': actors, 'site': site['id'], 'themeData': theme, 'stages': stages,
                         'routes': {k: author['routes'][k] for k in binding['routes']},
                         'rewards': {k: author['rewards'][k] for k in binding['rewardChoices']},
                         'service': {i: {'price': economy['goods'][i]['price'], 'count': 6} for i in theme['serviceItems']}})
    pack = {'schema': 'realmfolk.core-task-pack.experiment.v1', 'programs': programs, 'buildings': buildings,
            'itemNames': author['itemNames'], 'playtest': author['playtest'], 'sourceBlueprintHash': evidence['source']['blueprintHash'],
            'metrics': {'coreTypes': len(author['types']), 'aiPrograms': len(programs), 'generatedTasks': sum(len(p['stages']) for p in programs),
                        'aiInputCharacters': len(json.dumps(intent, ensure_ascii=False, separators=(',', ':')))},
            'boundaries': ['浏览器中的旅行、交谈、记录和物品均为隔离模拟。', '场所与人物来自学院 D4 和上轮规划结果，未在 Minecraft 中生成本轮任务。',
                           '未模拟法术教学、实际工业制作或地牢讨伐；三条供给途径使用明确的起始试验物资。']}
    pack['hash'] = digest(pack)
    return pack


def build():
    runtime, evidence, economy = (read(SOURCE / p) for p in ('runtime.json', 'evidence.json', 'economy.json'))
    for name,h in runtime['inputHashes'].items():
        require(file_hash(SOURCE / name) == h, '上轮正式规划器证据已过期: ' + name)
    author, intent = read(HERE / 'author-catalog.json'), read(HERE / 'ai-programs.json')
    pack = compile_pack(author, intent, evidence, runtime, economy)
    pack['inputHashes'] = {str(p.resolve()): file_hash(p) for p in [HERE/'author-catalog.json', HERE/'ai-programs.json', SOURCE/'evidence.json', SOURCE/'runtime.json', SOURCE/'economy.json']}
    # Hash includes all provenance and the compiled definitions.
    pack.pop('hash')
    pack['hash'] = digest(pack)
    write(OUTPUT / 'compiled.json', pack)
    return pack


def program(pack, ident):
    result = next((p for p in pack['programs'] if p['id'] == ident), None)
    require(result is not None, '未知任务方案')
    return result


def new_state(pack, program_id, loadout):
    p = program(pack, program_id)
    require(loadout in pack['playtest']['loadouts'], '未知起始身份')
    return {'packHash': pack['hash'], 'program': program_id, 'loadout': loadout, 'revision': 0, 'stage': 0,
            'place': p['actors'][0]['place'], 'inventory': copy.deepcopy(pack['playtest']['loadouts'][loadout]['items']),
            'coins': pack['playtest']['coins'], 'favor': 0, 'metHost': False, 'proof': False, 'rewardPending': False,
            'completed': False, 'route': None, 'rewardChoice': None, 'service': {}, 'known': [],
            'history': ['你来到学院，决定先认识这里的人。'], 'receipts': {}}


def perform(pack, original, request):
    require(original['packHash'] == pack['hash'], '任务内容已改变，请开始新的实验')
    require(set(request) <= {'action','target','eventId','expectedRevision'}, '未知操作字段')
    event = request.get('eventId')
    require(isinstance(event, str) and re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', event), '缺少有效操作 ID')
    signature = digest(request)
    if event in original['receipts']:
        require(original['receipts'][event]['request'] == signature, '同一操作 ID 不可替换内容')
        return copy.deepcopy(original)  # Replayed delivery never deducts or rewards twice.
    require(type(request.get('expectedRevision')) is int and request['expectedRevision'] == original['revision'], '页面已过期，请刷新')
    state = copy.deepcopy(original)
    p = program(pack, state['program'])
    host, partner = p['actors']
    action, target = request.get('action'), request.get('target')

    def log(text):
        state['history'].append(text)

    def finish_stage():
        reward = p['stages'][state['stage']]['reward']
        state['coins'] += reward['coins']
        state['favor'] += reward['favor']
        state['stage'] += 1

    if action == 'travel':
        require(target in {host['place'], partner['place'], p['site']}, '不能前往未绑定的地点')
        state['place'] = target
        log('前往 ' + ' / '.join(pack['buildings'][target]['terms'][:2]) + '。')
    elif action == 'talk':
        require(target in {host['id'], partner['id']}, '此人不在任务绑定中')
        actor = host if target == host['id'] else partner
        require(state['place'] == actor['place'], '请先前往这位居民所在的建筑')
        if state['completed']:
            log(actor['name'] + '：' + ('感谢你参与。' + p['themeData']['effect'] + '。' if actor == host else '我听说你已经和他们完成了合作，下次来这里就不用见外了。'))
        elif state['stage'] == 0:
            if actor == host:
                require(not state['metHost'], '已经听过介绍，可以去认识合作者')
                state['metHost'] = True
                state['known'].append(host['id'])
                log(host['name'] + '：' + p['welcome'])
            else:
                require(state['metHost'], '请先听取发起人的介绍')
                state['known'].append(partner['id'])
                log(partner['name'] + '：' + p['partnerLine'])
                finish_stage()
        else:
            log(actor['name'] + '：' + ('贡献材料之后，请到活动场所完成一次记录，再回来找我。' if state['stage'] == 1 else '你的准备已经帮上忙了。带回记录，我们就能把资料开放给来访者。'))
    elif action == 'contribute':
        require(state['stage'] == 1 and state['place'] == host['place'], '请在专业委托阶段向发起人交付')
        require(target in p['routes'], '作者没有开放这条贡献途径')
        route = p['routes'][target]
        require(state['inventory'].get(route['item'], 0) >= route['count'], '背包物资不足；不会凭按钮生成交付物')
        state['inventory'][route['item']] -= route['count']
        state['route'] = target
        log('你选择了「' + route['name'] + '」。' + route['contribution'] + '，团队可以开展下一步工作。')
        finish_stage()
    elif action == 'record':
        require(state['stage'] == 2 and state['place'] == p['site'] and not state['proof'], '请在项目阶段前往指定场所，记录只计一次')
        state['proof'] = True
        log('你在当地完成了「' + p['themeData']['activity'] + '」，取得' + p['themeData']['proof'] + '。')
    elif action == 'report':
        require(state['stage'] == 2 and state['proof'] and state['place'] == host['place'] and not state['rewardPending'], '需要把记录带回发起人，不能跳过实地环节')
        state['rewardPending'] = True
        log(host['name'] + '：记录和准备都齐了。谢谢你，请选择对自己有用的一份补给。')
    elif action == 'claim':
        require(state['stage'] == 2 and state['rewardPending'] and state['place'] == host['place'], '奖励尚不可领取')
        require(target in p['rewards'], '奖励不在作者允许范围内')
        for item,n in p['rewards'][target]['items'].items():
            state['inventory'][item] = state['inventory'].get(item, 0) + n
        state['rewardChoice'] = target
        finish_stage()
        state['completed'] = True
        state['rewardPending'] = False
        state['service'] = copy.deepcopy(p['service'])
        log('共同项目完成：' + p['themeData']['effect'] + '。你获得了「' + p['rewards'][target]['name'] + '」。')
    elif action == 'buy':
        require(state['completed'] and state['place'] == host['place'], '完成项目后在发起人处使用服务')
        require(target in state['service'], '服务没有这件物品')
        line = state['service'][target]
        require(line['count'] > 0 and state['coins'] >= line['price'], '库存或余额不足')
        state['coins'] -= line['price']
        line['count'] -= 1
        state['inventory'][target] = state['inventory'].get(target, 0) + 1
        log('从新开放的用品窗口购买了 ' + pack['itemNames'].get(target, target) + '。')
    else:
        raise ValueError('未知操作')
    require(state['coins'] >= 0 and all(n >= 0 for n in state['inventory'].values()), '状态不合法')
    state['revision'] += 1
    state['receipts'][event] = {'request': signature, 'revision': state['revision']}
    return state


def public_state(state):
    return {k:v for k,v in state.items() if k not in {'receipts'}}


def serve(pack, port, state_dir):
    state_dir.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def reply(self, value, code=200):
            raw = json.dumps(value, ensure_ascii=False).encode('utf-8')
            self.send_response(code); self.send_header('Content-Type','application/json; charset=utf-8')
            self.send_header('Content-Length',str(len(raw))); self.send_header('Cache-Control','no-store'); self.end_headers(); self.wfile.write(raw)

        def session_path(self, sid):
            require(isinstance(sid, str) and re.fullmatch('[a-f0-9]{32}',sid), '非法实验会话')
            return state_dir / (sid + '.json')

        def do_GET(self):
            if self.path == '/api/pack':
                return self.reply(pack)
            if self.path.startswith('/api/state?session='):
                try:
                    sid = self.path.split('=',1)[1]
                    with lock:
                        state = read(self.session_path(sid))
                    require(state['packHash'] == pack['hash'], '配置版本已改变，请重新开始实验')
                    return self.reply({'session':sid,'state':public_state(state)})
                except (ValueError,FileNotFoundError):
                    return self.reply({'error':'会话不存在或配置已更新，请重新开始'},404)
            if self.path not in ('/','/index.html','/favicon.ico'):
                return self.reply({'error':'not found'},404)
            if self.path == '/favicon.ico':
                self.send_response(204); self.end_headers(); return
            raw = (HERE/'index.html').read_bytes()
            self.send_response(200); self.send_header('Content-Type','text/html; charset=utf-8'); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)

        def do_POST(self):
            try:
                require(self.headers.get('Host') == '127.0.0.1:' + str(port), '仅接受本机实验地址')
                require(self.headers.get('Origin') == 'http://127.0.0.1:' + str(port) and self.headers.get('X-Task-Lab') == 'play', '只接受实验页面操作')
                length = int(self.headers.get('Content-Length','0'))
                require(0 < length <= 8192, '请求过大或为空')
                body = json.loads(self.rfile.read(length))
                with lock:
                    if self.path == '/api/new':
                        require(set(body) == {'program','loadout'}, '非法新会话字段')
                        sid = uuid.uuid4().hex
                        state = new_state(pack,body['program'],body['loadout'])
                    elif self.path == '/api/action':
                        require(set(body) == {'session','event'}, '非法操作字段')
                        sid = body['session']
                        state = perform(pack,read(self.session_path(sid)),body['event'])
                    else:
                        raise ValueError('未知请求路径')
                    write(self.session_path(sid),state)
                self.reply({'session':sid,'state':public_state(state)})
            except (ValueError,KeyError,FileNotFoundError,TypeError) as error:
                self.reply({'error':str(error)},409)

    print('Task experiment: http://127.0.0.1:' + str(port),flush=True)
    ThreadingHTTPServer(('127.0.0.1',port),Handler).serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serve',action='store_true')
    parser.add_argument('--port',type=int,default=8775)
    parser.add_argument('--state-dir',type=Path,default=HERE.parents[3]/'.realmfolk-build/core-task-lab/sessions')
    args = parser.parse_args()
    pack = build()
    print(json.dumps(pack['metrics'],ensure_ascii=False))
    if args.serve:
        serve(pack,args.port,args.state_dir)
