"""Synthetic fixtures exercise exact DEM consumption; they are never published as real scans."""
import copy,hashlib,json,tempfile,unittest
from pathlib import Path
import workbench as api
import dem_tools


def fixture(bounds):
    source=api.read(dem_tools.REVIEW)['terrainProvider']
    ground=[64]*((bounds['maxX']-bounds['minX']+1)*(bounds['maxZ']-bounds['minZ']+1))
    return {'schema':'city_terrain_dem.v1','cityId':api.read(dem_tools.TERRAIN)['cityId'],
        'd3TerrainHash':'sha256:'+hashlib.sha256(dem_tools.TERRAIN.read_bytes()).hexdigest(),
        'step':1,'order':'z_then_x','bounds':bounds,'source':{**source,'kind':'generator_native_height_field','testFixture':True},
        'groundY':ground,'waterSurfaceY':[None]*len(ground)}


class DemIntegration(unittest.TestCase):
    def test_exact_columns_no_interpolation_and_missing_coverage_fails(self):
        previous=api.OUTPUT
        with tempfile.TemporaryDirectory() as temp:
            api.OUTPUT=Path(temp)
            try:
                scene=api.read(api.SCENE);scene['terrainSource']='DEM_STEP_1'
                with self.assertRaisesRegex(ValueError,'尚未导入'):api.build(scene)
                box={'minX':-3100,'minZ':5100,'maxX':-2700,'maxZ':5520};dem=fixture(box)
                # A one-block depression and a wet column outside all structures must remain exact.
                width=box['maxX']-box['minX']+1
                def index(x,z):return (z-box['minZ'])*width+x-box['minX']
                dem['groundY'][index(-3020,5380)]=40
                dem['groundY'][index(-3021,5380)]=45;dem['waterSurfaceY'][index(-3021,5380)]=63
                dem_tools.install(dem,api.OUTPUT);meta=api.build(scene);folder=Path(meta['evidenceDirectory']);result=api.read(folder/'java-result.json')
                self.assertEqual(meta['terrain']['mode'],'DEM_STEP_1')
                samples={(r[0],r[1]):r[2:] for r in result['terrainColumns']}
                self.assertEqual(samples[-3020,5380],[40,'minecraft:grass_block',40])
                self.assertEqual(samples[-3019,5380],[64,'minecraft:grass_block',64])
                self.assertEqual(samples[-3021,5380],[63,'minecraft:water',45])
                self.assertEqual(result['terrainMode'],'DEM_STEP_1_GENERATOR_NATIVE_NO_INTERPOLATION')
                self.assertTrue((folder/'dem-step1.json').is_file())
                before=(api.OUTPUT/'state.json').read_bytes()
                dem_tools.install(fixture({'minX':-3000,'minZ':5300,'maxX':-2990,'maxZ':5310}),api.OUTPUT)
                with self.assertRaisesRegex(ValueError,'DEM must cover'):api.build(scene,meta['revision'])
                self.assertEqual((api.OUTPUT/'state.json').read_bytes(),before)
            finally:api.OUTPUT=previous

    def test_identity_coverage_and_integer_heights(self):
        dem=fixture({'minX':-3000,'minZ':5300,'maxX':-2999,'maxZ':5301})
        dem_tools.validate_dem(dem)
        for change in ('city','hash','provider','hole','fraction','water'):
            bad=copy.deepcopy(dem)
            if change=='city':bad['cityId']='other'
            elif change=='hash':bad['d3TerrainHash']='different'
            elif change=='provider':bad['source']['sourceFingerprint']='different'
            elif change=='hole':bad['groundY'].pop()
            elif change=='fraction':bad['groundY'][0]=64.5
            else:bad['waterSurfaceY'][0]=60
            with self.assertRaises(ValueError,msg=change):dem_tools.validate_dem(bad)


if __name__=='__main__':unittest.main(verbosity=2)
