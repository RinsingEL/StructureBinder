import unittest
from connection_terraces import apply_connection_terraces,synchronize_road_sides
from test_gap_landscape import fixture


class ConnectionTerraces(unittest.TestCase):
    def synchronize(self,ground,occupied=None):
        r={'scene':{},'roadBands':[dict(bounds=dict(minX=0,maxX=0,minZ=0,maxZ=0),axisX=1,axisZ=0)],'surfacePrintPlan':{'featureCells':[dict(x=9,z=z,kind='ROAD_SLAB',surfaceOffset=0) for z in range(30)]}}
        targets={(x,15):(64,(9,15),9-x) for x in (6,7,8)};pairs={p:('a','b') for p in targets}
        return synchronize_road_sides(r,targets,ground,occupied or {},pairs)

    def test_single_trigger_populates_opposite_verge(self):
        ground={(x,z):(62,'minecraft:grass_block') for x in range(25) for z in range(30)}
        targets,added=self.synchronize(ground)
        self.assertEqual(set(added),{(10,15),(11,15),(12,15)})
        self.assertTrue(all(targets[p][0]==64 for p in added))

    def test_opposite_verge_stops_at_existing_platform(self):
        ground={(x,z):(62,'minecraft:grass_block') for x in range(25) for z in range(30)}
        _,added=self.synchronize(ground,{(11,15):'existing'})
        self.assertEqual(added,[(10,15)])

    def test_opposite_verge_does_not_fill_water_or_valley(self):
        for water in (True,False):
            ground={(x,z):(62,'minecraft:grass_block') for x in range(25) for z in range(30)}
            ground[(11,15)]=(62,'minecraft:water') if water else (20,'minecraft:stone')
            _,added=self.synchronize(ground)
            self.assertEqual(added,[])

    def test_structural_support_and_internal_guard_removal(self):
        r=fixture();r['scene']={'gapSurface':'CONNECTION_TERRACE','gapLandscape':True}
        for c in r['terrainColumns']:c[2]=62
        r['surfacePrintPlan']={'featureCells':[dict(x=9,z=z,targetSurfaceY=64,kind='ROAD_SLAB',surfaceOffset=0) for z in range(30)]}
        r['mutations']=[[2,65,15,'minecraft:stone_brick_wall','TERRACE_EDGE_RAIL']]
        cells={(x,y,z):{'name':block} for x,z,y,block in r['terrainColumns']};sources={p:'terrain' for p in cells}
        cells[(2,65,15)]={'name':'minecraft:stone_brick_wall'};sources[(2,65,15)]='infrastructure'
        def put(x,y,z,block,layer):
            if block=='minecraft:air':cells.pop((x,y,z),None);sources.pop((x,y,z),None)
            else:cells[(x,y,z)]={'name':block};sources[(x,y,z)]=layer
        apply_connection_terraces(r,cells,sources,put);plan=r['connectionTerracePlan']
        self.assertEqual(cells[(8,64,15)]['name'],'minecraft:smooth_stone')
        self.assertEqual(cells[(8,63,15)]['name'],'minecraft:stone_bricks')
        self.assertNotIn((2,65,15),cells)
        self.assertIn([2,65,15],plan['removedInternalEdges'])
        self.assertTrue(all(c['x']!=9 for c in plan['columns']))


if __name__=='__main__':unittest.main()
