import copy,unittest
from city_fringe import apply_fringe,apply_access

class Fringe(unittest.TestCase):
 def fixture(self):
  cells={(x,0,z):{'name':'minecraft:grass_block'} for x in range(-15,16) for z in range(-15,16)}
  sources={p:'terrain' for p in cells}
  r={'scene':{'outerGreenery':{'width':8}},'terrainColumns':[[x,z,0,'minecraft:grass_block'] for x,y,z in cells],
     'terraces':[{'spans':[dict(z=z,minX=-4,maxX=4) for z in range(-4,5)]}]}
  def put(x,y,z,s,layer):cells[x,y,z]={'name':s};sources[x,y,z]=layer
  return r,cells,sources,put
 def test_exterior_respects_band_height_and_water(self):
  r,c,s,put=self.fixture()
  for row in r['terrainColumns']:
   if row[0]==10:row[3]='minecraft:water'
  p=apply_fringe(r,c,s,put)
  self.assertTrue(p['writes'])
  for x,y,z,k in p['writes']:
   self.assertEqual(y,0);self.assertGreaterEqual(max(abs(x)-4,0)+max(abs(z)-4,0),3);self.assertFalse(8<=x<=12)
 def test_enclosed_courtyard_is_not_exterior(self):
  r,c,s,put=self.fixture()
  r['terraces'][0]['spans']=[dict(z=z,minX=-8,maxX=8) for z in (-8,8)]+[dict(z=z,minX=x,maxX=x) for z in range(-7,8) for x in (-8,8)]
  p=apply_fringe(r,c,s,put)
  self.assertTrue(all(abs(x)>8 or abs(z)>8 for x,y,z,k in p['writes']))
 def test_disabled_greenery(self):
  r,c,s,put=self.fixture();r['scene']['greenery']=False;self.assertIsNone(apply_fringe(r,c,s,put))
 def test_harbor_reservation_stays_empty(self):
  r,c,s,put=self.fixture();r['externalAccessPlan']={'columns':[],'harborReservation':dict(minX=5,maxX=15,minZ=-15,maxZ=15)}
  self.assertTrue(all(x<5 for x,y,z,k in apply_fringe(r,c,s,put)['writes']))
 def test_access_rejects_water_without_writes(self):
  r,c,s,put=self.fixture();r['scene']['externalAccess']={'width':3,'waypoints':[dict(x=0,z=0,surface=.5),dict(x=10,z=0,surface=.5)],'landing':dict(minX=9,maxX=11,minZ=-1,maxZ=1),'harborReservation':dict(minX=9,maxX=12,minZ=-2,maxZ=2)}
  r['members']=[];r['surfacePrintPlan']={'featureCells':[dict(x=0,z=0,targetSurfaceY=0,kind='ROAD_SLAB',surfaceOffset=0)]}
  for row in r['terrainColumns']:
   if row[:2]==[5,0]:row[3]='minecraft:water'
  before=copy.deepcopy(c)
  with self.assertRaises(ValueError):apply_access(r,c,s,put)
  self.assertEqual(c,before)

if __name__=='__main__':unittest.main()
