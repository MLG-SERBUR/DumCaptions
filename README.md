# DumCaptions

A high-performance Java-based Discord bot that provides real-time voice captions and translations using Discord's DAVE (End-to-End Encryption) protocol.

## Key Features

- **Real-Time Voice Captions**: Translates and transcribes voice chat conversations in real-time.
- **Discord DAVE Support**: Implements the latest secure voice protocol using `libdave-jvm`.
- **High-Performance VAD**: Uses `TEN-VAD` (via JNA) for accurate speech detection and noise filtering.
- **Powered by Groq**: Leverages `whisper-large-v3` for lightning-fast, high-quality translation.
- **Non-Destructive Registration**: Slash commands are registered globally without overwriting other bots' commands.

## Usage

### Prerequisites

1.  **Java 25 (or later)**: Required for the Foreign Function & Memory (FFM) API.
2.  **Runtime Libraries**: Required for native audio components.
    - Ubuntu/Debian: `sudo apt-get install libc++1 libc++abi1`
3.  **Discord Bot Token**: Create at [Discord Developer Portal](https://discord.com/developers/applications).
4.  **Groq API Key**: Get your key at [Groq Console](https://console.groq.com/).

### Configuration

1.  Create `config.json` in the project root (use `config.example.json` as a template):
    ```json
    {
      "discord_token": "YOUR_DISCORD_TOKEN",
      "groq_api_key": "YOUR_GROQ_API_KEY",
      "stt_model": "whisper-large-v3",
      "captions_enabled": true,
      "vad_mode": "adaptive",
      "translate_api_key": "YOUR_TRANSLATEAPI_KEY",
      "mymemory_email": "YOUR_EMAIL@example.com",
      "backend": "TranslateAPI",
      "interaction_select_enabled": true,
      "target_channels": []
    }
    ```

    `vad_mode` supports:
    - `ratio`: current strict percentage-based gate
    - `adaptive`: keeps the strict gate, but also rescues sparse speech spread across a longer buffer

    Translation settings:
    - `translate_api_key`: API key for the TranslateAPI backend.
    - `mymemory_email`: optional email for the MyMemory backend.
    - `backend`: default backend for newly enabled channels. Supported values: `TranslateAPI`, `MyMemory`.
    - `interaction_select_enabled`: show backend dropdown on translated webhook messages by default.
    - `target_channels`: channel IDs to enable for translation on startup.

    Channel settings persist in `channels.json`.

### Running the Bot
Since this bot uses native features and the latest Java APIs, you must enable native access:
```bash
java --enable-native-access=ALL-UNNAMED -jar target/dum-captions-1.0-SNAPSHOT-shaded.jar
```

### Commands
- `/captions on` - Start real-time captions in your current voice channel.
- `/captions off` - Stop captions and leave the voice channel.
- `/translate` - Show translation status for the current text channel.
- `/translate enabled:on` - Enable Arabic/Korean text translation in the current channel.
- `/translate enabled:off` - Disable text translation in the current channel.
- `/translate backend:MyMemory` - Change backend for an enabled channel.
- `/translate interaction_selection:off` - Hide the backend dropdown on translated messages.

Text translation uses webhooks to mirror the original sender name and avatar. The bot needs `Manage Webhooks`, `Send Messages`, `Use Application Commands`, and `Read Message History` in translated channels.

## Development

- **VAD Configuration**: Choose the runtime mode in `config.json`; tune hardcoded thresholds in `CaptionsConfig.java`.
- **Audio Processing**: Handles stereo 48kHz Opus audio packets directly from Discord.
- **Ogg Container**: Generates Groq-compliant Ogg/Opus streams with valid CRC-32 checksums.

### Building

1.  **Maven**: For building the project.
2.  **C/C++ Build Tools**: Only if you need to recompile the native components.
    - Ubuntu/Debian: `sudo apt-get install gcc build-essential`

1.  Clone this repository.
2.  Build the shaded JAR:
    ```bash
    mvn clean package
    ```

---

## Adaptive VAD system

### Spanning

In this VAD system, **Span** refers to the **temporal distance** between the very first speech hit and the very last speech hit in an audio buffer.

Think of it as the "time envelope" of the speech activity.

### How it is calculated:
The code tracks the index of the first frame where speech was detected and the index of the last frame. 
> **Span = (Last Speech Frame Index - First Speech Frame Index) + 1**

### Why it matters:
The "Span" helps the bot distinguish between a **short noise** and **sparse speech**:

1.  **A short noise (like a mouse click or a pop):** Might have 4 frames of speech, but they all happen right next to each other. The span would be very small (e.g., `span=4`).
2.  **Sparse speech (like a soft sentence):** Might also only have 4 frames that are loud enough to trigger the VAD, but those frames are spread out. In your example log (`span=91`), the first "hit" and the last "hit" were 91 frames apart. 

