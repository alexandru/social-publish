# Frontend Scala - ScalaJS + Vite + React

This is a ScalaJS-based frontend implementation using Vite and React. It's a rewrite of the TypeScript frontend in `../frontend/` with the same design and structure.

## Prerequisites

- JDK 17 or later
- Node.js and npm
- sbt (included as `../sbt` wrapper)

## Development

### Running the development server

The development setup requires two terminals running in parallel:

**Terminal 1 - SBT (for Scala.js compilation):**
```bash
cd /path/to/social-publish
./sbt "~frontendScala/fastLinkJS"
```

**Terminal 2 - Vite (for dev server):**
```bash
cd frontend-scala
# Make sure sbt is in PATH or use:
export PATH="$(cd .. && pwd):$PATH"
npm run dev
```

The dev server will be available at http://localhost:5174/

### Building for production

```bash
# First compile Scala.js to optimized JS
cd /path/to/social-publish
./sbt "frontendScala/fullLinkJS"

# Then build with Vite
cd frontend-scala
export PATH="$(cd .. && pwd):$PATH"
npm run build
```

The production build will be in `frontend-scala/dist/`.

## Technology Stack

- **ScalaJS 1.19.0** - Scala to JavaScript compiler
- **scalajs-react 3.0.0-beta7** - React facade for Scala.js
- **Vite 7.3.0** - Fast build tool and dev server
- **React 18.3.1** - UI library
- **Bulma 1.0.4** - CSS framework

## Project Structure

```
frontend-scala/
├── src/
│   ├── main/scala/socialpublish/frontend/
│   │   ├── Main.scala              # Entry point
│   │   ├── components/
│   │   │   └── NavBar.scala        # Navigation bar component
│   │   └── pages/
│   │       └── Home.scala          # Home page component
│   └── style.css                   # CSS imports (Bulma)
├── public/
│   ├── assets/                     # Static assets (logos, etc.)
│   └── manifest.json               # PWA manifest
├── index.html                      # HTML entry point
├── vite.config.js                  # Vite configuration
├── package.json                    # npm dependencies
└── README.md                       # This file
```

## Notes

- This implementation uses scalajs-react which provides type-safe React bindings for Scala
- The CSS and design are copied from the original TypeScript frontend
- Components are currently static (no interactivity yet)
- React 18 is used for compatibility with scalajs-react 3.0.0-beta7
