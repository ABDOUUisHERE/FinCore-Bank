"""Regression tests for the existing Keycloak provisioning utility, not business services."""
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error

class ReachedAuthorizedUpdate(RuntimeError):
    pass

class BusinessIdentityTest(unittest.TestCase):
    def exercise(self, expire_before_update=False):
        spec=importlib.util.spec_from_file_location("business_identity",Path(__file__).resolve().parents[1]/"configure-business-clients.py")
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        clock=[0];issued=[];calls=[]
        def transport(request,*args,**kwargs):
            url=request if isinstance(request,str) else request.full_url
            if url.endswith("/protocol/openid-connect/token"):
                token="test-token-"+str(len(issued)+1);issued.append(token)
                return io.BytesIO(json.dumps({"access_token":token,"expires_in":60}).encode())
            bearer=request.get_header("Authorization");calls.append(bearer)
            if request.get_method()=="GET":
                if expire_before_update:clock[0]=61
                return io.BytesIO(b'[{"id":"ledger-id"}]')
            if bearer!="Bearer test-token-2":
                raise urllib.error.HTTPError(url,401,"expired",None,None)
            raise ReachedAuthorizedUpdate()
        # Same real nested api() path as the setup job; no credentials or real HTTP.
        with patch.dict("os.environ",{"KEYCLOAK_ADMIN_PASSWORD":"synthetic"}), patch("urllib.request.urlopen",side_effect=transport), patch("time.monotonic",side_effect=lambda:clock[0]):
            with self.assertRaises(ReachedAuthorizedUpdate):module.main()
        self.assertEqual(len(issued),2)
        self.assertEqual(calls[-1],"Bearer test-token-2")

    def test_proactively_renews_before_a_slow_configuration_outlives_its_token(self):
        self.exercise(expire_before_update=True)

    def test_retries_once_with_a_fresh_token_after_an_unexpected_unauthorized_response(self):
        self.exercise()

if __name__=="__main__":unittest.main()
