# App Word Game

A mobile word game application (resembling Scrabble) built with a focus on **Peer-to-Peer (P2P)** connectivity. Unlike traditional games that rely on a central server, this project leverages WebRTC to allow players to connect directly with each other.

## 🚀 Current Project State

The project is currently in an active development phase where the core game mechanics and the P2P communication layer are functional but operating as independent modules.

### **1. Core Game Engine**
- **Board Logic**: 15x15 grid with support for special multipliers (DL, TL, DW, TW).
- **Tile Management**: Full tile bag system with randomized distribution and player racks.
- **Validation**: Dictionary-based word verification using local assets.
- **Scoring**: Automated calculation of points based on tile values and board multipliers.
- **UI**: Reactive interface built with Jetpack Compose, supporting drag-and-drop-like placement (tap-to-place).

### **2. P2P Connectivity (WebRTC)**
- **Signaling**: Manual signaling implementation where players exchange SDP Offers/Answers and ICE candidates via text.
- **Data Channels**: Established WebRTC Data Channels for low-latency communication.
- **Chat**: Functional P2P chat system to verify connectivity.
- **Mesh Support**: Underlying architecture supports multiple peers (mesh networking).

### **3. Tech Stack**
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: MVVM (Model-View-ViewModel) with Kotlin Coroutines and Flow.
- **Networking**: WebRTC (Native Android implementation).
- **Dependency Injection**: Manual/Application-level state management.

## 🛠 Features in Progress
- **Game-WebRTC Integration**: Bridging the local `GameEngine` with the `WebRtcChatEngine` to synchronize moves across devices.
- **Automated Signaling**: Moving away from manual copy-pasting to a more user-friendly connection method (e.g., QR codes or a lightweight signaling relay).
- **Game State Synchronization**: Implementing a protocol to handle "First Player" selection and shared random seeds for the tile bag.

## 📖 How to Run
1. Clone the repository.
2. Open in **Android Studio**.
3. Build and run on two separate devices or emulators.
4. Play the game.
