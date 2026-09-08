#!/usr/bin/env python3
"""Minimal Efficy e-deal mock: base_data Opportunity create, Person lookup by PerMail, referential_for.
Stores opportunities in memory; GET /__mock/opportunities lists them, DELETE /__mock/opportunities resets."""
import json, sys, itertools
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, unquote

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8092
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "mock-efficy-token"
APP = "MockContext"
OPPS = {}
IDS = itertools.count(0x284d550)
PERSONS = {"ada@example.com": {"PerID": "0000000000005f14", "PerEntID": "0000000000004fb3"}}
REFERENTIALS = {
    "OppStoID": [("000000000000074f", "NEED", "Lead"), ("000000000007f426", "PROPAE", "Devis a emettre"), ("0000000000000746", "PROP", "Devis envoye")],
    "OppOpbID": [("0000000000000b54", "20", "20%"), ("0000000000000b55", "50", "50%"), ("0000000000000b56", "80", "80%")],
    "OppGammeShouhaitee_": [("000000000086cdda", "SANTE", "Sante"), ("000000000086cde1", "PREVOYANCE", "Prevoyance"), ("000000000086cde5", "EPARGNE", "Epargne")],
}

def envelope(api_type, data=None, status="OK", code=None, message=None):
    return {"api_version": "1.0", "api_type": api_type, "return_status": status, "error_code": code, "error_message": message,
            "response_locale": "fr_FR", "response_timezone": "Europe/Paris", "data": data}

class H(BaseHTTPRequestHandler):
    def _send(self, code, body=None, raw=None):
        data = raw.encode() if raw is not None else (b"" if body is None else json.dumps(body).encode())
        self.send_response(code)
        self.send_header("Content-Type", "application/json" if raw is None else "text/plain")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode() or "{}") if n else {}

    def _auth(self):
        if self.headers.get("Authorization", "") != TOKEN:
            self._send(401, envelope("base_data", status="KO", code="401", message="Unauthorized"))
            return False
        return True

    def log_message(self, fmt, *args):
        sys.stderr.write((fmt % args) + "\n")

    def do_GET(self):
        p = urlparse(self.path)
        if p.path == "/__mock/opportunities":
            return self._send(200, list(OPPS.values()))
        if not self._auth():
            return
        qs = parse_qs(p.query)
        if p.path == f"/{APP}/api/service/1.0/referential_for":
            field = qs.get("field", [""])[0]
            if field not in REFERENTIALS:
                return self._send(500, envelope("service", status="OK", code="OTHER_ERROR", message=f'Internal error. : [DataDictionary] can\'t find field with sql name "{field}"'))
            rows = [{"id": i, "code": c, "te1": l, "nu1": None, "order": n, "enable": True} for n, (i, c, l) in enumerate(REFERENTIALS[field])]
            return self._send(200, envelope("service", rows))
        if p.path == f"/{APP}/api/base_data/1.0/Person":
            flt = unquote(qs.get("filter", [""])[0])
            if not flt:
                return self._send(400, raw="null parameter is mandatory.")
            email = flt.split("PerMail,=,")[1].split("]")[0] if "PerMail,=," in flt else ""
            hits = [dict(PERSONS[email]) for e in [email] if e in PERSONS]
            return self._send(200, envelope("base_data", {"query_type": "restricted", "nb_of_result": len(hits), "query_results": hits}))
        return self._send(404, envelope("base_data", status="KO", code="404", message="Not found"))

    def do_POST(self):
        p = urlparse(self.path)
        if not self._auth():
            return
        if p.path == f"/{APP}/api/base_data/1.0/Opportunity":
            bean = (self._body().get("data") or {}).get("bean_data") or {}
            missing = [f for f in ("OppTitle", "OppEntID", "OppPerID", "OppStoID", "OppOpbID", "OppDate", "OppStake") if bean.get(f) in (None, "")]
            if missing:
                return self._send(400, envelope("base_data", status="KO", code="400", message=f"Missing mandatory fields: {missing}"))
            if bean.get("OppTitle") == "REJECT":
                return self._send(400, envelope("base_data", status="KO", code="BEAN_VALIDATION_ERROR", message="Title refused by the mock"))
            oid = "%016x" % next(IDS)
            OPPS[oid] = dict(bean, OppID=oid)
            return self._send(200, envelope("base_data", {"bean_data": OPPS[oid], "bean_display": bean.get("OppTitle")}))
        return self._send(404, envelope("base_data", status="KO", code="404", message="Not found"))

    def do_DELETE(self):
        if urlparse(self.path).path == "/__mock/opportunities":
            OPPS.clear()
            return self._send(204)
        return self._send(404)

if __name__ == "__main__":
    print(f"mock efficy on :{PORT}, app context {APP}", flush=True)
    HTTPServer(("0.0.0.0", PORT), H).serve_forever()
