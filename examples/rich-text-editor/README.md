# Rich Text Editor

This demo shows a real-time collaborative rich text editor built with Jetpack Compose and Yorkie Android SDK. It's the Android equivalent of the Next.js Quill example, providing rich text editing capabilities with real-time collaboration.

## Features

- **Rich Text Editing**: Bold, italic, underline, and strikethrough formatting
- **Real-time Collaboration**: Multiple users can edit the same document simultaneously
- **Live Cursors**: See other users' selections and cursors in real-time
- **Document Synchronization**: Automatic synchronization of changes across all connected clients
- **Modern UI**: Built with Jetpack Compose and Material Design 3

## How to run demo

### With Yorkie Dashboard

1. Create an account on [Yorkie Dashboard](https://yorkie.dev/dashboard)
2. Create a new project and copy your public key from the dashboard
3. Update the `local.properties` file in the root directory:

```properties
YORKIE_SERVER_URL=https://api.yorkie.dev
YORKIE_API_KEY=your_key_xxxx
```

4. Build and run the app:

```bash
./gradlew :examples:rich-text-editor:assembleDebug
```

### With local Yorkie server

1. At project root, run below command to start Yorkie:

```bash
docker compose -f docker/docker-compose.yml up --build -d
```

2. Update the `local.properties` file:

```properties
YORKIE_SERVER_URL=http://localhost:8080
YORKIE_API_KEY=
```

3. Build and run the app:

```bash
./gradlew :examples:rich-text-editor:assembleDebug
```

## Architecture

The app follows MVVM architecture with the following components:

- **MainActivity**: Entry point using Jetpack Compose
- **EditorViewModel**: Handles Yorkie SDK integration and business logic
- **RichTextEditor**: Compose UI component for text editing with formatting toolbar
- **RichTextEditorScreen**: Main screen composable with participants and document info
- **DocumentInfoSection**: Shows real-time document state and JSON

## Key Components

### EditorViewModel
- Manages Yorkie client connection and document synchronization
- Handles real-time text changes and presence updates
- Provides reactive state for UI components

### RichTextEditor
- Custom Compose component with formatting toolbar
- Supports bold, italic, underline, and strikethrough
- Real-time text synchronization with other users

### Collaboration Features
- **Presence**: Shows connected users
- **Live Selections**: Visual indicators for other users' cursors
- **Real-time Sync**: Automatic document synchronization
- **Conflict Resolution**: Handles concurrent edits gracefully

## Comparison with Next.js Quill Example

This Android app provides equivalent functionality to the Next.js Quill example:

| Feature | Next.js Quill | Android Rich Text Editor |
|----------|---------------|------------------------------|
| Rich Text Editing | Quill.js | Custom Compose components |
| Real-time Collaboration | Yorkie JS SDK | Yorkie Android SDK |
| UI Framework | React + Tailwind | Jetpack Compose + Material 3 |
| Text Formatting | Quill toolbar | Custom formatting toolbar |
| Document Info | React components | Compose components |

## Dependencies

- **Yorkie Android SDK**: For real-time collaboration
- **Jetpack Compose**: For modern UI
- **Material Design 3**: For consistent theming
- **OkHttp**: For network communication
- **Gson**: For JSON serialization

## Development

To contribute to this example:

1. Follow the existing code structure
2. Use Compose best practices for UI components
3. Maintain proper separation of concerns with ViewModel
4. Test with multiple devices for collaboration features
5. Ensure proper error handling and loading states
