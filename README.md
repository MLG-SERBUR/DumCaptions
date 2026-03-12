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
      "groq_api_key": "YOUR_GROQ_API_KEY"
    }
    ```

### Running the Bot
Since this bot uses native features and the latest Java APIs, you must enable native access:
```bash
java --enable-native-access=ALL-UNNAMED -jar target/dum-captions-1.0-SNAPSHOT-shaded.jar
```

### Commands
- `/captions on` - Start real-time captions in your current voice channel.
- `/captions off` - Stop captions and leave the voice channel.

## Development

- **VAD Configuration**: Adjust thresholds in `CaptionsConfig.java`.
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
Built with JDA, libdave-jvm, and Groq | [GitHub](https://github.com/MLG-SERBUR/DumTranslator)
