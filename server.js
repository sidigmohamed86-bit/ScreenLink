const express = require("express");
const http = require("http");
const path = require("path");
const { Server } = require("socket.io");

const app = express();
const server = http.createServer(app);

const io = new Server(server, {
  cors: {
    origin: true,
    credentials: false
  }
});

const PORT = process.env.PORT || 3000;
const rooms = new Map();

app.disable("x-powered-by");

// Website files are in the repository root
app.use(express.static(__dirname));

app.get("/health", (req, res) => {
  res.json({
    ok: true,
    service: "ScreenLink"
  });
});

io.on("connection", (socket) => {
  socket.on("create-room", ({ room }) => {
    if (!room) return;

    if (rooms.has(room)) {
      socket.emit("room-error", "Room already exists.");
      return;
    }

    rooms.set(room, {
      host: socket.id,
      viewer: null
    });

    socket.join(room);
    socket.data.room = room;
    socket.data.role = "host";

    socket.emit("room-created", room);
  });

  socket.on("join-room", ({ room }) => {
    const data = rooms.get(room);

    if (!data) {
      socket.emit("room-error", "Room not found.");
      return;
    }

    if (data.viewer) {
      socket.emit("room-error", "Room already has a viewer.");
      return;
    }

    data.viewer = socket.id;

    socket.join(room);
    socket.data.room = room;
    socket.data.role = "viewer";

    io.to(data.host).emit("peer-joined");
    socket.emit("joined-room", room);
  });

  socket.on("offer", ({ room, offer }) => {
    const data = rooms.get(room);

    if (!data || !data.viewer) return;

    io.to(data.viewer).emit("offer", offer);
  });

  socket.on("answer", ({ room, answer }) => {
    const data = rooms.get(room);

    if (!data || !data.host) return;

    io.to(data.host).emit("answer", answer);
  });

  socket.on("ice-candidate", ({ room, candidate }) => {
    const data = rooms.get(room);

    if (!data) return;

    const target =
      socket.id === data.host
        ? data.viewer
        : data.host;

    if (target) {
      io.to(target).emit("ice-candidate", candidate);
    }
  });

  socket.on("stop-share", ({ room }) => {
    const data = rooms.get(room);

    if (!data) return;

    io.to(room).emit("share-stopped");
  });

  socket.on("disconnect", () => {
    const room = socket.data.room;

    if (!room) return;

    const data = rooms.get(room);

    if (!data) return;

    if (socket.id === data.host) {
      io.to(room).emit("host-disconnected");
      rooms.delete(room);
    } else if (socket.id === data.viewer) {
      data.viewer = null;
      io.to(data.host).emit("viewer-disconnected");
    }
  });
});

// Send index.html for normal website routes
app.use((req, res) => {
  res.sendFile(path.join(__dirname, "index.html"));
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`ScreenLink running on port ${PORT}`);
});
