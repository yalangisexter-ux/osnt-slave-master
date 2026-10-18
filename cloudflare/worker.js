const ROOM_RE = /^[A-Za-z0-9_-]{16,64}$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return Response.json({ ok: true, service: "peer-signaling", storesPeerPayloads: false });
    }
    const match = url.pathname.match(/^\/signal\/([A-Za-z0-9_-]{16,64})$/);
    if (!match || !ROOM_RE.test(match[1])) return new Response("Use /signal/<random-room-code>", { status: 404 });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("WebSocket upgrade required", { status: 426 });
    const id = env.SIGNAL_ROOMS.idFromName(match[1]);
    return env.SIGNAL_ROOMS.get(id).fetch(request);
  }
};

export class SignalRoom {
  constructor(state) {
    this.state = state;
    this.sockets = new Set();
  }

  async fetch(request) {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();
    this.sockets.add(server);
    server.addEventListener("message", event => {
      const data = event.data;
      if (typeof data !== "string" || data.length > 16384) {
        try { server.send(JSON.stringify({ type: "error", message: "Invalid signaling frame" })); } catch (_) {}
        return;
      }
      for (const peer of this.sockets) {
        if (peer !== server && peer.readyState === WebSocket.OPEN) {
          try { peer.send(data); } catch (_) {}
        }
      }
    });
    const remove = () => this.sockets.delete(server);
    server.addEventListener("close", remove);
    server.addEventListener("error", remove);
    server.send(JSON.stringify({ type: "ready", peers: this.sockets.size - 1 }));
    return new Response(null, { status: 101, webSocket: client });
  }
}
