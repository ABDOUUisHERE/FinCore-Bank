"""Private notification DLQ operations; run through the Compose tools profile."""
import json,os,sys,urllib.request,urllib.parse,urllib.error

def main():
    action=sys.argv[1] if len(sys.argv)>1 else "list"
    form=urllib.parse.urlencode({"grant_type":"client_credentials","client_id":"fincore-notification-operator","client_secret":os.environ["NOTIFICATION_OPERATOR_SECRET"]}).encode()
    with urllib.request.urlopen(os.environ["KEYCLOAK_URL"]+"/realms/fincore/protocol/openid-connect/token",form,timeout=15) as r:token=json.load(r)["access_token"]
    path="/internal/v1/notifications/dead-letters";data=None;method="GET"
    if action=="replay":
        import uuid
        identifier=str(uuid.UUID(sys.argv[2]));version=int(sys.argv[3]);reason=sys.argv[4]
        path+="/"+identifier+"/replay";data=json.dumps({"expectedVersion":version,"reason":reason}).encode();method="POST"
    elif action!="list":raise ValueError("Invalid action")
    request=urllib.request.Request(os.environ["NOTIFICATION_URL"]+path,data,{"Authorization":"Bearer "+token,"Content-Type":"application/json"},method=method)
    with urllib.request.urlopen(request,timeout=15) as r:print(json.dumps(json.load(r)))
if __name__=="__main__":
    try:main()
    except (ValueError,KeyError,IndexError,urllib.error.URLError):raise SystemExit("Notification operation could not be confirmed; verify scope, version and service readiness.") from None
