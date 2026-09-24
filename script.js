const socket = io();

const $ = id => document.getElementById(id);
const createBtn = $("createBtn");
const joinBtn = $("joinBtn");
const shareBtn = $("shareBtn");
const stopBtn = $("stopBtn");
const copyBtn = $("copyBtn");
const shareLinkBtn = $("shareLinkBtn");
const fullscreenBtn = $("fullscreenBtn");
const roomInput = $("roomInput");
const roomBox = $("roomBox");
const roomCodeEl = $("roomCode");
const statusEl = $("status");
const messageEl = $("message");
const hostControls = $("hostControls");
const remoteVideo = $("remoteVideo");
const localVideo = $("localVideo");
const localSection = $("localSection");
const placeholder = $("placeholder");
const remoteStatus = $("remoteStatus");
const audioBox = $("audioBox");

let roomCode = null;
let isHost = false;
let peer = null;
let localStream = null;
let pendingCandidates = [];
let remoteDescriptionSet = false;

const rtcConfig = {
  iceServers: [
    { urls: "stun:stun.l.google.com:19302" },
    { urls: "stun:stun.cloudflare.com:3478" }
  ]
};

function setStatus(text, ok=false) {
  statusEl.textContent = text;
  statusEl.className = "status" + (ok ? " ok" : "");
}

function message(text) { messageEl.textContent = text; }

function makeCode() {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let s = "";
  for (let i=0;i<6;i++) s += chars[Math.floor(Math.random()*chars.length)];
  return s;
}

function showRoom(code) {
  roomCode = code;
  roomCodeEl.textContent = code;
  roomBox.classList.remove("hidden");
}

function createPeer() {
  peer = new RTCPeerConnection(rtcConfig);

  peer.onicecandidate = e => {
    if (e.candidate) socket.emit("ice-candidate", {
      room: roomCode,
      candidate: e.candidate
    });
  };

  peer.ontrack = e => {
    let stream = e.streams && e.streams[0];

    if (!stream) {
      if (!remoteVideo.srcObject) {
        remoteVideo.srcObject = new MediaStream();
      }
      remoteVideo.srcObject.addTrack(e.track);
      stream = remoteVideo.srcObject;
    } else {
      remoteVideo.srcObject = stream;
    }

    placeholder.classList.add("hidden");
    remoteStatus.textContent = "Live";

    remoteVideo.onloadedmetadata = () => {
      remoteVideo.play().catch(() => {
        message("Tap the video to start playback.");
      });
    };

    remoteVideo.play().catch(() => {});
  };

  peer.onconnectionstatechange = () => {
    const s = peer.connectionState;
    if (s === "connected") {
      setStatus("Connected", true);
      remoteStatus.textContent = "Connected";
    } else if (["failed","disconnected","closed"].includes(s)) {
      remoteStatus.textContent = "Disconnected";
    }
  };
}

async function addPendingCandidates() {
  for (const c of pendingCandidates) {
    try { await peer.addIceCandidate(c); } catch(e) {}
  }
  pendingCandidates = [];
}

async function startHostCall() {
  if (!peer || !localStream) return;
  const offer = await peer.createOffer();
  await peer.setLocalDescription(offer);
  socket.emit("offer", { room: roomCode, offer: peer.localDescription });
}

async function startShare() {
  if (!isHost) return message("Only the host can share.");
  if (!peer) return message("Wait for the other device to join.");

  try {
    localStream = await navigator.mediaDevices.getDisplayMedia({
      video: { width:{ideal:1920}, height:{ideal:1080}, frameRate:{ideal:60,max:60} },
      audio: audioBox.checked
    });

    localVideo.srcObject = localStream;
    localSection.classList.remove("hidden");
    shareBtn.disabled = true;
    stopBtn.disabled = false;
    message("Your screen is being shared.");

    const track = localStream.getVideoTracks()[0];
    if (track) track.addEventListener("ended", stopShare);

    for (const track of localStream.getTracks()) {
      peer.addTrack(track, localStream);
    }

    await startHostCall();
  } catch (e) {
    message("Screen sharing was cancelled or unavailable.");
  }
}

function stopShare() {
  if (localStream) {
    localStream.getTracks().forEach(t => t.stop());
    localStream = null;
  }
  localVideo.srcObject = null;
  localSection.classList.add("hidden");
  shareBtn.disabled = false;
  stopBtn.disabled = true;
  remoteStatus.textContent = "Waiting";
  message("Screen sharing stopped.");
  socket.emit("stop-share", {room: roomCode});
}

createBtn.onclick = () => {
  if (roomCode) return;
  const code = makeCode();
  socket.emit("create-room", { room: code });
};

joinBtn.onclick = () => {
  const code = roomInput.value.trim().toUpperCase();
  if (!/^[A-Z0-9]{6}$/.test(code)) return message("Enter a valid 6-character room code.");
  socket.emit("join-room", { room: code });
};

roomInput.onkeydown = e => {
  if (e.key === "Enter") joinBtn.click();
};

copyBtn.onclick = async () => {
  if (!roomCode) return;
  await navigator.clipboard.writeText(roomCode);
  message("Room code copied.");
};

shareLinkBtn.onclick = async () => {
  if (!roomCode) return;
  const url = location.origin + "/?room=" + roomCode;
  await navigator.clipboard.writeText(url);
  message("Room link copied.");
};

shareBtn.onclick = startShare;
stopBtn.onclick = stopShare;

fullscreenBtn.onclick = async () => {
  try {
    if (!document.fullscreenElement) await remoteVideo.requestFullscreen();
    else await document.exitFullscreen();
  } catch(e) {}
};

socket.on("connect", () => setStatus("Online", true));

socket.on("room-created", (room) => {
  isHost = true;
  showRoom(room);
  hostControls.classList.remove("hidden");
  setStatus("Waiting", false);
  message("Room created. Open this same website on the other device and join.");
});

socket.on("joined-room", (room) => {
  isHost = false;
  showRoom(room);
  setStatus("Connecting");
  message("Joined. Waiting for the host's screen.");
});

socket.on("peer-joined", () => {
  if (isHost) {
    createPeer();
    message("Device connected. You can now share your screen.");
    setStatus("Connected", true);
  }
});

socket.on("offer", async (offer) => {
  if (isHost) return;

  try {
    createPeer();
    await peer.setRemoteDescription(offer);
    remoteDescriptionSet = true;
    await addPendingCandidates();

    const answer = await peer.createAnswer();
    await peer.setLocalDescription(answer);
    socket.emit("answer", { room: roomCode, answer: peer.localDescription });
  } catch (e) {
    console.error("Offer error:", e);
    message("Could not connect to the host.");
  }
});

socket.on("answer", async (answer) => {
  if (!peer) return;

  try {
    await peer.setRemoteDescription(answer);
    remoteDescriptionSet = true;
    await addPendingCandidates();
  } catch (e) {
    console.error("Answer error:", e);
  }
});

socket.on("ice-candidate", async (candidate) => {
  if (!peer || !peer.remoteDescription) {
    pendingCandidates.push(candidate);
    return;
  }

  try {
    await peer.addIceCandidate(candidate);
  } catch(e) {
    console.error("ICE candidate error:", e);
  }
});

socket.on("share-stopped", () => {
  remoteVideo.srcObject = null;
  placeholder.classList.remove("hidden");
  remoteStatus.textContent = "Waiting";
});

socket.on("peer-left", () => {
  remoteVideo.srcObject = null;
  placeholder.classList.remove("hidden");
  remoteStatus.textContent = "Device disconnected";
  setStatus("Disconnected");
});

socket.on("error-message", ({message: m}) => message(m));

const params = new URLSearchParams(location.search);
const initialRoom = params.get("room");
if (initialRoom) roomInput.value = initialRoom.toUpperCase();
