# Cobbie 0.4.0 Build Plan

## Current Status
- build.gradle: Fabric Loom 1.17-SNAPSHOT, MC 26.3, JDK 25
- fabric.mod.json: entrypoints main + client
- All Java files compile successfully
- GeckoLib dependency causes crash on MC 26.3 (IncompatibleClassChangeError)

## Tasks

### 1. Fix GeckoLib Dependency Issue (CRITICAL)
- Problem: GeckoLib 5.5.7's GeoEntityRenderer overrides now-final EntityRenderer.createRenderState on MC 26.3
- Solution: Remove GeckoLib dependency, use vanilla renderer
- Files: build.gradle, client/CobbieClient.java, client/CobbieRenderer.java

### 2. Fix Memory Layer
- Problem: FabricLoader config dir access issues on some systems
- Solution: Use FabricLoader.getInstance().getConfigDir() properly with fallbacks
- Files: memory/CobbieMemory.java

### 3. Fix WorldPerception
- Problem: Entity and perception incomplete
- Solution: Complete perception implementation with proper entity detection
- Files: world/WorldPerception.java, world/WorldSnapshot.java

### 4. Fix ActionParser
- Problem: JSON parsing and action parsing issues
- Solution: Improve JSON parsing robustness
- Files: brain/ActionParser.java

### 5. Fix Pathfinder
- Problem: A* pathfinder implementation incomplete
- Solution: Complete A* pathfinder implementation
- Files: action/Pathfinder.java

### 6. Build and Deploy
- Build with ./gradlew clean jar
- Deploy to mods folder
- Test in-game

## Files Modified
- build.gradle - Remove GeckoLib dependency
- src/main/java/com/rishi/cobbie/client/CobbieClient.java - Use vanilla renderer
- src/main/java/com/rishi/cobbie/client/CobbieVanillaRenderer.java - Create vanilla renderer
- src/main/java/com/rishi/cobbie/memory/CobbieMemory.java - Fix memory persistence
- src/main/java/com/rishi\cobbie\world\WorldPerception.java - Fix perception
- src/main/java/com/rishi\cobbie\brain\ActionParser.java - Fix JSON parsing
- src/main/java/com/rishi\cobbie\action\Pathfinder.java - Fix pathfinder

## Estimated Time: 30-45 minutes
