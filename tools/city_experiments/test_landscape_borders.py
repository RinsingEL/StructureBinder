import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'structure_studio'))
from landscape_borders import contour_tiles


class BorderContours(unittest.TestCase):
    def test_rectangle_corners_rotate_toward_plants(self):
        mask={(x,z) for x in range(9) for z in range(7)}
        tiles=list(contour_tiles(mask));corners={p:r for p,k,r in tiles if k=='outer'}
        self.assertEqual(corners,{(0,0):0,(8,0):90,(8,6):180,(0,6):270})
        self.assertEqual(len(tiles),28)
        self.assertTrue(all(p in mask for p,_,_ in tiles))

    def test_irregular_mask_produces_inner_joint(self):
        mask={(x,z) for x in range(8) for z in range(8) if x<4 or z<4}
        self.assertTrue(any(k=='inner' for _,k,_ in contour_tiles(mask)))

    def test_single_cell_and_line_do_not_become_closed_flowerbed(self):
        self.assertEqual(list(contour_tiles({(0,0)})),[])
        self.assertEqual(list(contour_tiles({(x,0) for x in range(8)})),[])


if __name__=='__main__':unittest.main()
