#!/usr/bin/env python3
"""Minimal HubSpot CRM v3 mock: contact properties, contact create/update, search by email.
Stores contacts in memory; GET /__mock/contacts lists them, DELETE /__mock/contacts resets."""
import json, sys, itertools
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8091
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "pat-mock-token"
CONTACTS = {}
IDS = itertools.count(1001)

def prop(name, label, type_, field_type, options=None, read_only=False, hidden=False, calculated=False):
    return {"name": name, "label": label, "type": type_, "fieldType": field_type, "hidden": hidden, "calculated": calculated,
            "archived": False, "modificationMetadata": {"readOnlyValue": read_only},
            "options": [{"value": v, "label": v.title(), "hidden": False} for v in (options or [])]}

PROPERTIES = {"results": [
    prop("hs_object_id", "Record ID", "number", "number", read_only=True),
    prop("email", "Email", "string", "text"),
    prop("firstname", "First Name", "string", "text"),
    prop("lastname", "Last Name", "string", "text"),
    prop("company", "Company Name", "string", "text"),
    prop("phone", "Phone Number", "string", "phonenumber"),
    prop("jobtitle", "Job Title", "string", "text"),
    prop("website", "Website URL", "string", "text"),
    prop("message", "Message", "string", "textarea"),
    prop("hs_lead_status", "Lead Status", "enumeration", "select", ["NEW", "OPEN", "IN_PROGRESS"]),
    prop("industry", "Industry", "enumeration", "select", ["BANKING", "TECHNOLOGY"]),
    prop("interests", "Interests", "enumeration", "checkbox", ["cms", "dxp", "cdp"]),
    prop("newsletter_optin", "Newsletter opt-in", "bool", "booleancheckbox", ["true", "false"]),
    prop("numemployees", "Number of Employees", "number", "number"),
    prop("demo_date", "Demo date", "date", "date"),
    prop("callback_at", "Callback", "datetime", "date"),
    prop("hs_calculated_score", "Score", "number", "calculation_equation", calculated=True),
    prop("hidden_prop", "Hidden", "string", "text", hidden=True),
]}

def error(status, category, message, props=None):
    body = {"status": "error", "message": message, "category": category, "correlationId": "mock"}
    if props:
        body["errors"] = [{"message": message, "context": {"propertyName": props}}]
    return status, body

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
        return json.loads(self.rfile.read(n).decode() or "{}") if n else {}

    def _auth(self):
        if self.headers.get("Authorization", "") != "Bearer " + TOKEN:
            self._send(*error(401, "INVALID_AUTHENTICATION", "Authentication credentials not found."))
            return False
        return True

    def log_message(self, fmt, *args):
        sys.stderr.write((fmt % args) + "\n")

    def do_GET(self):
        p = urlparse(self.path)
        if p.path == "/__mock/contacts":
            return self._send(200, list(CONTACTS.values()))
        if not self._auth():
            return
        if p.path == "/crm/v3/properties/contacts":
            return self._send(200, PROPERTIES)
        if p.path == "/crm/v3/objects/contacts":
            return self._send(200, {"results": list(CONTACTS.values())[:1]})
        return self._send(*error(404, "OBJECT_NOT_FOUND", "resource not found"))

    def do_POST(self):
        p = urlparse(self.path)
        if not self._auth():
            return
        if p.path == "/crm/v3/objects/contacts/search":
            body = self._body()
            email = None
            for group in body.get("filterGroups", []):
                for f in group.get("filters", []):
                    if f.get("propertyName") == "email":
                        email = f.get("value")
            hits = [c for c in CONTACTS.values() if c["properties"].get("email") == email]
            return self._send(200, {"total": len(hits), "results": hits[:1]})
        if p.path == "/crm/v3/objects/contacts":
            props = self._body().get("properties", {})
            if not props:
                return self._send(*error(400, "VALIDATION_ERROR", "Properties are required"))
            email = props.get("email")
            if email == "reject@example.com":
                return self._send(*error(400, "VALIDATION_ERROR", "Property values were not valid", ["email"]))
            for c in CONTACTS.values():
                if email and c["properties"].get("email") == email:
                    return self._send(*error(409, "CONFLICT", f"Contact already exists. Existing ID: {c['id']}"))
            cid = str(next(IDS))
            CONTACTS[cid] = {"id": cid, "properties": dict(props)}
            return self._send(201, CONTACTS[cid])
        return self._send(*error(404, "OBJECT_NOT_FOUND", "resource not found"))

    def do_PATCH(self):
        if not self._auth():
            return
        cid = urlparse(self.path).path.rstrip("/").split("/")[-1]
        if cid not in CONTACTS:
            return self._send(*error(404, "OBJECT_NOT_FOUND", "resource not found"))
        CONTACTS[cid]["properties"].update(self._body().get("properties", {}))
        CONTACTS[cid]["_updated"] = True
        return self._send(200, CONTACTS[cid])

    def do_DELETE(self):
        if urlparse(self.path).path == "/__mock/contacts":
            CONTACTS.clear()
            return self._send(204)
        return self._send(404)

if __name__ == "__main__":
    print(f"mock hubspot on :{PORT}", flush=True)
    HTTPServer(("0.0.0.0", PORT), H).serve_forever()
