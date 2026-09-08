// Test-fixture transformation only: retain the returned test package's design.
import {readFileSync,writeFileSync} from 'node:fs';
import {dirname,join} from 'node:path';
import {fileURLToPath} from 'node:url';
const dir=dirname(fileURLToPath(import.meta.url));
const read=p=>JSON.parse(readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const prepared=JSON.parse(read(join(dir,'08_prepare_capital.result.json')).response.result.content[0].text);
const blueprint=read('E:/Mod_Dev/StructureBinder/run/saves/RTF_designfirst_20260907/realm_debug/'+prepared.artifacts.testRunPackage+'/steps/blueprint/city_blueprint.json');
delete blueprint.sourceD3Ref;
delete blueprint.catalogSnapshotRef;
const request={recordName:'09_submit_capital',method:'tools/call',params:{name:'city_submit_d4_blueprint',arguments:{runId:prepared.cityBlueprintContext.runId,citySeedId:blueprint.cityId,contextId:prepared.contextId,cityBlueprint:blueprint,autoAdvanceAfterD4:false}}};
writeFileSync(join(dir,'09_submit_capital.json'),JSON.stringify(request,null,2));
console.log('Prepared identical design replay; only D3/catalog identity is rebound by the host.');
