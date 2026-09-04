#!/usr/bin/env python3
"""Minimal Salesforce mock: JWT token endpoint, Lead describe, Lead create/update, SOQL by email.
Stores leads in memory; GET /__mock/leads lists them, DELETE /__mock/leads resets."""
import json, sys, uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, unquote

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
PUBLIC = sys.argv[2] if len(sys.argv) > 2 else f"http://host.docker.internal:{PORT}"
LEADS = {}
LOG = []

def field(name, label, type_, createable=True, nillable=True, defaulted=False, length=0, picklist=None):
    return {"name": name, "label": label, "type": type_, "createable": createable, "nillable": nillable,
            "defaultedOnCreate": defaulted, "length": length,
            "picklistValues": [{"value": v, "label": v, "active": True} for v in (picklist or [])]}

DESCRIBE = {"name": "Lead", "fields": [
    field("Id", "Lead ID", "id", createable=False, nillable=False, defaulted=True),
    field("LastName", "Last Name", "string", nillable=False, length=80),
    field("FirstName", "First Name", "string", length=40),
    field("Company", "Company", "string", nillable=False, length=255),
    field("Email", "Email", "email", length=80),
    field("Phone", "Phone", "phone", length=40),
    field("Title", "Title", "string", length=128),
    field("Website", "Website", "url", length=255),
    field("Description", "Description", "textarea", length=32000),
    field("LeadSource", "Lead Source", "picklist", picklist=["Web", "Phone Inquiry", "Partner Referral", "Other"]),
    field("Industry", "Industry", "picklist", picklist=["Agriculture", "Banking", "Technology"]),
    field("NumberOfEmployees", "Employees", "int"),
    field("AnnualRevenue", "Annual Revenue", "currency"),
    field("DoNotCall", "Do Not Call", "boolean", nillable=False, defaulted=True),
    field("Interests__c", "Interests", "multipicklist", picklist=["CMS", "DXP", "CDP"]),
    field("Demo_Date__c", "Demo Date", "date"),
    field("Callback__c", "Callback", "datetime"),
    field("IsConverted", "Converted", "boolean", createable=False, nillable=False, defaulted=True),
]}

class H(BaseHTTPRequestHandler):
    def _send(self, code, body=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(code)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode() if n else ""

    def _auth_ok(self):
        return self.headers.get("Authorization", "").startswith("Bearer mock-token-")

    def log_message(self, fmt, *args):
        LOG.append(fmt % args)
        sys.stderr.write((fmt % args) + "\n")

    def do_POST(self):
        p = urlparse(self.path)
        if p.path == "/services/oauth2/token":
            form = parse_qs(self._body())
            if form.get("grant_type", [""])[0] != "urn:ietf:params:oauth:grant-type:jwt-bearer" or len(form.get("assertion", [""])[0].split(".")) != 3:
                return self._send(400, {"error": "invalid_grant", "error_description": "bad assertion"})
            return self._send(200, {"access_token": "mock-token-" + uuid.uuid4().hex[:8], "instance_url": PUBLIC, "token_type": "Bearer"})
        if not self._auth_ok():
            return self._send(401, [{"message": "Session expired or invalid", "errorCode": "INVALID_SESSION_ID"}])
        if p.path.endswith("/sobjects/Lead"):
            lead = json.loads(self._body() or "{}")
            missing = [f for f in ("LastName", "Company") if not lead.get(f)]
            if missing:
                return self._send(400, [{"message": "Required fields are missing: " + str(missing), "errorCode": "REQUIRED_FIELD_MISSING", "fields": missing}])
            if lead.get("Email") == "reject@example.com":
                return self._send(400, [{"message": "Custom validation failed", "errorCode": "FIELD_CUSTOM_VALIDATION_EXCEPTION", "fields": ["Email"]}])
            lid = "00Q" + uuid.uuid4().hex[:15].upper()
            LEADS[lid] = dict(lead, Id=lid, IsConverted=False)
            return self._send(201, {"id": lid, "success": True, "errors": []})
        return self._send(404, [{"message": "Not found", "errorCode": "NOT_FOUND"}])

    def do_PATCH(self):
        if not self._auth_ok():
            return self._send(401, [{"message": "Session expired or invalid", "errorCode": "INVALID_SESSION_ID"}])
        lid = self.path.rstrip("/").split("/")[-1]
        if lid not in LEADS:
            return self._send(404, [{"message": "Not found", "errorCode": "NOT_FOUND"}])
        LEADS[lid].update(json.loads(self._body() or "{}"))
        LEADS[lid]["_updated"] = True
        return self._send(204)

    def do_GET(self):
        p = urlparse(self.path)
        if p.path == "/__mock/leads":
            return self._send(200, list(LEADS.values()))
        if not self._auth_ok():
            return self._send(401, [{"message": "Session expired or invalid", "errorCode": "INVALID_SESSION_ID"}])
        if p.path.endswith("/sobjects/Lead/describe"):
            return self._send(200, DESCRIBE)
        if p.path.endswith("/limits"):
            return self._send(200, {"DailyApiRequests": {"Max": 100000, "Remaining": 99999}})
        if p.path.endswith("/query"):
            q = unquote(parse_qs(p.query).get("q", [""])[0])
            email = q.split("Email = '")[1].split("'")[0] if "Email = '" in q else None
            recs = [{"attributes": {"type": "Lead"}, "Id": l["Id"]} for l in LEADS.values() if l.get("Email") == email and not l.get("IsConverted")]
            return self._send(200, {"totalSize": len(recs), "done": True, "records": recs})
        return self._send(404, [{"message": "Not found", "errorCode": "NOT_FOUND"}])

    def do_DELETE(self):
        if urlparse(self.path).path == "/__mock/leads":
            LEADS.clear()
            return self._send(204)
        return self._send(404)

if __name__ == "__main__":
    print(f"mock salesforce on :{PORT}, public {PUBLIC}", flush=True)
    HTTPServer(("0.0.0.0", PORT), H).serve_forever()
