# Frontend Scala - ScalaJS + ScalablyTyped + React

This is a ScalaJS-based frontend using **ScalablyTyped** to automatically generate Scala type bindings from React's TypeScript definitions. This allows using React directly in Scala without wrapper libraries.

## Status: IN PROGRESS ⚠️

ScalablyTyped is successfully generating Scala types from React's TypeScript definitions, but the component implementations need to be updated to properly use the generated types.

## Prerequisites

- JDK 17 or later
- Node.js and npm/yarn
- sbt (included as `../sbt` wrapper)

## Technology Stack

- **ScalaJS 1.19.0** - Scala to JavaScript compiler
- **sbt-scalajs-bundler 0.21.1** - npm dependency management for ScalaJS
- **ScalablyTyped 1.0.0-beta44** - Automatic TypeScript → Scala type generation
- **Scala 2.13.16** - Required for ScalablyTyped compatibility
- **React 18.3.1** - UI library with auto-generated Scala facades
- **Bulma 1.0.4** - CSS framework

## Build Configuration

The project uses **sbt-scalajs-bundler** instead of Vite. This is required for ScalablyTyped to manage npm dependencies and generate types.

## Development

### Compiling Scala.js

```bash
cd /path/to/social-publish
./sbt "frontendScala/fastLinkJS"
```

This will:
1. Install npm dependencies via yarn
2. Generate Scala type bindings from TypeScript definitions (first time takes ~2-3 minutes)
3. Compile Scala code to JavaScript

Generated bindings are cached in `~/.ivy2/local/org.scalablytyped/`

### Current Issues

The component code currently doesn't compile because it needs to use the proper types from the ScalablyTyped-generated React bindings.

## Next Steps

1. Review the generated types in `target/.../stImport/`
2. Update components to use proper React types from `typings.react.mod._`
3. Create helper functions to simplify React.createElement usage
4. Set up dev server workflow

## Project Structure

```
frontend-scala/
├── src/main/scala/socialpublish/frontend/
│   ├── Main.scala              # Entry point
│   ├── components/             # React components
│   │   └── NavBar.scala        
│   └── pages/                  # Page components
│       └── Home.scala          
├── public/
│   ├── assets/                 # Static assets
│   └── manifest.json           
└── target/
    └── ...stImport/            # Generated Scala types from TypeScript
```

## Notes

- **No wrapper libraries**: Uses React directly via ScalablyTyped-generated types
- **CommonJS modules**: Required by sbt-scalajs-bundler
- **Scala 2.13**: ScalablyTyped doesn't support Scala 3.7.4 yet
- **Type safety**: Full type safety from TypeScript definitions → Scala types

## Resources

- [ScalablyTyped Documentation](https://scalablytyped.org/)
- [ScalablyTyped React Demos](https://github.com/ScalablyTyped/ScalaJsReactDemos)
- [sbt-scalajs-bundler](https://scalacenter.github.io/scalajs-bundler/)
