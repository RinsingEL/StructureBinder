import copy
import hashlib
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
from threading import Thread
import unittest
from unittest.mock import patch

from studio.frontage import resolve, runtime_frontage, save
from studio.model import Model, sha256, write_json
from studio.server import Handler


class FrontageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.model().export(self.path)
        self.author = json.loads((self.path / 'author.json').read_text(encoding='utf-8'))

    def model(self):
        model = Model('T', 'two entrances', (4, 4, 4)).set(0, 0, 0, 'stone')
        model.point('entry', 'entrance', (1, 1, 0), 'front side', facing='north')
        model.point('side', 'entrance', (3, 1, 1), 'side', facing='east')
        return model

    def request(self, **changes):
        return dict(id='T', author_sha256=sha256(self.path / 'author.json'),
                    nbt_sha256=sha256(self.path / 'structure.nbt'),
                    policy='FIXED_FRONT', entrance_id='side', **changes)

    def ports(self, author=None):
        return [dict(entranceId=p['id'], position=dict(x=p['pos'][0], z=p['pos'][2]), direction=p['facing'].upper())
                for p in (author or self.author)['points']]

    def test_manual_save_preserves_nbt_and_old_evidence_and_exports_selected_side(self):
        review = self.path / 'review.json'
        review.write_text('{"status":"accepted","author_sha256":"historical"}')
        nbt_before, review_before = sha256(self.path / 'structure.nbt'), review.read_bytes()
        self.assertEqual('entry', resolve(self.author)['entrance_id'])
        defaults, _ = runtime_frontage(self.author, self.ports())
        self.assertEqual(['front', 'side'], [p['entranceId'] for p in defaults])
        result = save(self.path, self.request())
        ports, policy = runtime_frontage(result['author'], self.ports())
        self.assertEqual('FIXED_FRONT', policy)
        self.assertEqual(['entry', 'front'], [p['entranceId'] for p in ports])
        self.assertEqual('EAST', ports[1]['direction'])
        self.assertEqual(self.ports()[1]['position'], ports[1]['position'])
        self.assertEqual(nbt_before, sha256(self.path / 'structure.nbt'))
        self.assertEqual(review_before, review.read_bytes())
        self.assertEqual(self.author['points'], result['author']['points'])

    def test_conflicts_and_invalid_choice_cannot_overwrite_author(self):
        request = self.request()
        save(self.path, request)
        before = (self.path / 'author.json').read_bytes()
        with self.assertRaises(FileExistsError):
            save(self.path, request)
        bad = self.request(); bad['entrance_id'] = 'nonexistent'
        with self.assertRaises(ValueError):
            save(self.path, bad)
        self.assertEqual(before, (self.path / 'author.json').read_bytes())

    def test_rebuild_preserves_manual_choice_but_changed_geometry_requires_review(self):
        saved = save(self.path, self.request())['author']['frontage']
        self.model().export(self.path)
        author = json.loads((self.path / 'author.json').read_text(encoding='utf-8'))
        self.assertEqual(saved, author['frontage'])
        self.assertEqual('ready', resolve(author)['status'])
        changed = self.model().set(2, 0, 2, 'stone')
        changed.export(self.path)
        author = json.loads((self.path / 'author.json').read_text(encoding='utf-8'))
        self.assertEqual(saved, author['frontage'])
        self.assertEqual('stale', resolve(author)['status'])
        with self.assertRaisesRegex(ValueError, 'STUDIO_FRONTAGE_REQUIRED'):
            runtime_frontage(author, self.ports())

    def test_changed_entrance_facing_is_stale_and_any_mode_removes_implicit_front(self):
        saved = save(self.path, self.request())['author']
        saved['points'][0]['facing'] = 'south'
        self.assertEqual('stale', resolve(saved)['status'])
        author = copy.deepcopy(self.author)
        author['points'][0]['id'] = 'front'
        write_json(self.path / 'author.json', author)
        request = self.request(); request.update(policy='ANY_AUTHORED_ENTRANCE', entrance_id='')
        result = save(self.path, request)
        ports, policy = runtime_frontage(result['author'], self.ports(author))
        self.assertEqual('ANY_AUTHORED_ENTRANCE', policy)
        self.assertFalse(any(p['entranceId'].lower() == 'front' for p in ports))
        self.assertEqual(['NORTH', 'EAST'], [p['direction'] for p in ports])

    def test_single_and_named_front_remain_compatible_without_guessing(self):
        author = copy.deepcopy(self.author); author['points'] = author['points'][:1]
        self.assertEqual('entry', resolve(author)['entrance_id'])
        author = copy.deepcopy(self.author); author['points'][1]['id'] = 'front'
        self.assertEqual('front', resolve(author)['entrance_id'])

    def test_http_write_is_confined_same_origin_and_optimistic(self):
        with patch('studio.server.asset_paths', return_value={'T': self.path}):
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            thread = Thread(target=server.serve_forever, daemon=True); thread.start()
            self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
            port = server.server_port
            def post(body, origin=None):
                connection = HTTPConnection('127.0.0.1', port)
                connection.request('POST', '/api/frontage', json.dumps(body),
                                   {'Content-Type': 'application/json', 'X-Studio-Write': 'frontage',
                                    'Origin': origin or f'http://127.0.0.1:{port}'})
                response = connection.getresponse()
                data = json.loads(response.read()); status = response.status; connection.close()
                return status, data
            body = self.request()
            before = sha256(self.path / 'author.json')
            self.assertEqual(403, post(body, 'https://other.example')[0])
            self.assertEqual(404, post({**body, 'id': '../../other'})[0])
            self.assertEqual(before, sha256(self.path / 'author.json'))
            status, result = post(body)
            self.assertEqual(200, status)
            self.assertEqual('side', result['author']['frontage']['entrance_id'])
            self.assertEqual(409, post(body)[0])


if __name__ == '__main__':
    unittest.main()
