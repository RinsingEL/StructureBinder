"""Render the actual Python compiler and Java runtime results as a standalone review."""
import html
import json
from pathlib import Path
from experiment import HERE, read, write, fingerprint


def render():
    output = HERE / 'results/academy'
    report = read(output / 'report.json')
    runtime_file = HERE.parents[2] / '.realmfolk-build/reports/fourth-question/runtime.json'
    runtime = read(runtime_file)
    if runtime['sourceBlueprintHash'] != report['source']['blueprintHash']:
        raise ValueError('Java report belongs to a different accepted D4 blueprint')
    for name in ('submission.json', 'evidence.json', 'economy.json'):
        if runtime.get('inputHashes', {}).get(name) != fingerprint(output / name):
            raise ValueError('Java result is stale; rerun the experiment test: ' + name)
    evidence = read(output / 'evidence.json')
    report['runtime'] = runtime
    report['buildings'] = [{k: a[k] for k in ('anchorId', 'templateId', 'placementGroupId', 'plannedFootprint')} for a in evidence['anchors']['anchors']]
    data = json.dumps(report, ensure_ascii=False).replace('<', '\\u003c')
    template = (HERE / 'report.html').read_text(encoding='utf-8')
    (output / 'index.html').write_text(template.replace('/*__DATA__*/{}', data), encoding='utf-8')
    write(output / 'runtime.json', runtime)
    metrics = report['metrics']
    summary = f'''# 第四题 · 魔法学院配置实验

离线实验；没有创建游戏内 NPC 或写入游戏存档。

## 实际结果

- 使用已接受 D4 的 {metrics['retainedBuildings']} 栋建筑和冻结用途。
- AI 输入 {metrics['aiGroups']} 个群体、{metrics['aiInputCharacters']} 字符；展开 {metrics['compiledProfessions']} 种职业、{metrics['functionRows']} 行功能安排、{metrics['expandedCharacters']} 字符的正式提交。
- 正式 Java 校验通过；假定住宅站立名额可用时，规划 {runtime['population']['created']} 人，住房 {runtime['population']['housingBuildings']} 栋，名额 {runtime['population']['housingSlots']}。
- 生成 {len(report['deliveryDemands'])} 类物资委托，来源是已配置商品池；没有证明附近野外资源或即时库存。
- 未发出附近讨伐委托：缺少本学院的实际目标证据和正式任务计数接口。

## 需要继续开发的边界

'''+''.join('- '+x+'\n' for x in report['limitations'])+'''
## 查看与复现

浏览器打开 `index.html`，选择群体可查看正式规划器生成的居民、库存、赠礼概率及委托。
输入分别为 `../../academy.intent.json`（本轮 AI 选择）和 `../../author-policy.json`（作者复用规则）。
正式展开为 `submission.json`；本页仅供 review，不能直接拿实验 baseRevision 发布到存档。
来源路径、蓝图及输入文件 hash 见 `report.json` / `evidence.json`。
'''
    (output / 'README.md').write_text(summary, encoding='utf-8')
    print(json.dumps({'population': runtime['population'], 'groups': runtime['groupCounts'], 'report': str(output / 'index.html')}, ensure_ascii=False))


if __name__ == '__main__':
    render()
