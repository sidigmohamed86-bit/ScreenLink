const express = require("express");
const http = require("http");
const path = require("path");
const { Server } = require("socket.io");

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: true, credentials: false }
});

const PORT = process.env.PORT || 3000;
const rooms = new Map();

app.disable("x-powered-by");
app.use(express.static(path.join(__dirname, "public")));

app.get("/health", (req, res) => {
  res.json({ ok: true, service: "ScreenLink" });
});

app.use((req, res) => {
  
res.sendFile(path.join(__dirname, "public", "index.html"));
});

function validRoom(room) {
  return typeof room === "string" && /^[A-Z0-9]{6}$/.test(room);
}

io.on("connection", socket => {
  socket.on("create-room", ({room}) => {
    if (!validRoom(room)) return socket.emit("error-message", {message:"Invalid room code."});
    if (rooms.has(room)) return socket.emit("error-message", {message:"That room already exists. Try again."});

    rooms.set(room, {host: socket.id, viewer: null});
    socket.join(room);
    socket.data.room = room;
    socket.data.role = "host";
    socket.emit("room-created", {room});
  });

  socket.on("join-room", ({room}) => {
    if (!validRoom(room)) return socket.emit("error-message", {message:"Invalid room code."});
    const r = rooms.get(room);
    if (!r) return socket.emit("error-message", {message:"Room not found."});
    if (r.viewer && r.viewer !== socket.id) return socket.emit("error-message", {message:"Room is already in use."});

    r.viewer = socket.id;
    socket.join(room);
    socket.data.room = room;
    socket.data.role = "viewer";

    socket.emit("joined", {room});
    io.to(r.host).emit("peer-joined");
  });

  socket.on("offer", ({room, offer}) => {
    const r = rooms.get(room);
    if (r?.viewer) io.to(r.viewer).emit("offer", {offer});
  });

  socket.on("answer", ({room, answer}) => {
    const r = rooms.get(room);
    if (r?.host) io.to(r.host).emit("answer", {answer});
  });

  socket.on("ice-candidate", ({room, candidate}) => {
    const r = rooms.get(room);
    if (!r) return;
    const target = socket.id === r.host ? r.viewer : r.host;
    if (target) io.to(target).emit("ice-candidate", {candidate});
  });

  socket.on("stop-share", ({room}) => {
    if (validRoom(room)) socket.to(room).emit("share-stopped");
  });

  socket.on("disconnect", () => {
    const room = socket.data.room;
    if (!room) return;
    const r = rooms.get(room);
    if (!r) return;

    if (socket.id === r.host) {
      io.to(room).emit("peer-left");
      rooms.delete(room);
    } else if (socket.id === r.viewer) {
      r.viewer = null;
      io.to(r.host).emit("peer-left");
    }
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`ScreenLink running on port ${PORT}`);
});
