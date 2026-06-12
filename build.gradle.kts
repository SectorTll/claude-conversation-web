plugins {
	java
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "ee.doniss"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// Web Push (VAPID) with pure-JDK crypto and no transitive HTTP/JSON stacks (kotlin-stdlib only).
	implementation("com.interaso:webpush:1.2.0")
	// Full-text index for global search (Lucene 10.x requires JDK 21 — matches the toolchain).
	implementation("org.apache.lucene:lucene-core:10.4.0")
	implementation("org.apache.lucene:lucene-analysis-common:10.4.0")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// Frontend (Vue 3 + Vite) build, baked into the backend's static resources so a
// single `java -jar` / `./gradlew bootRun` serves the whole app from 127.0.0.1.
// Pass -PskipFrontend to skip (backend-only builds without Node).
// ---------------------------------------------------------------------------
val isWindows = System.getProperty("os.name").lowercase().contains("win")
fun npm(vararg args: String): List<String> =
	if (isWindows) listOf("cmd", "/c", "npm", *args) else listOf("npm", *args)

val frontendInstall by tasks.registering(Exec::class) {
	group = "frontend"
	description = "npm install for the Vue frontend"
	workingDir = file("frontend")
	inputs.files("frontend/package.json", "frontend/package-lock.json")
	outputs.upToDateWhen { file("frontend/node_modules").isDirectory }
	commandLine(npm("install", "--no-fund", "--no-audit"))
}

val frontendBuild by tasks.registering(Exec::class) {
	group = "frontend"
	description = "Type-check + Vite build into src/main/resources/static"
	dependsOn(frontendInstall)
	workingDir = file("frontend")
	inputs.dir("frontend/src")
	inputs.files(
		"frontend/package.json", "frontend/index.html",
		"frontend/vite.config.ts", "frontend/tsconfig.json", "frontend/env.d.ts",
	)
	outputs.dir(layout.projectDirectory.dir("src/main/resources/static"))
	commandLine(npm("run", "build"))
}

if (!project.hasProperty("skipFrontend")) {
	tasks.named("processResources") { dependsOn(frontendBuild) }
}

// ---------------------------------------------------------------------------
// Chat sidecar (Node + Claude Agent SDK), bundled by esbuild to a single
// sidecar.mjs and baked into the jar's resources; SidecarLocator extracts it at
// runtime (or prefers sidecar/dist directly in dev). Pass -PskipSidecar to skip.
// ---------------------------------------------------------------------------
val sidecarInstall by tasks.registering(Exec::class) {
	group = "sidecar"
	description = "npm install for the chat sidecar"
	workingDir = file("sidecar")
	inputs.files("sidecar/package.json", "sidecar/package-lock.json")
	outputs.upToDateWhen { file("sidecar/node_modules").isDirectory }
	commandLine(npm("install", "--no-fund", "--no-audit"))
}

val sidecarBuild by tasks.registering(Exec::class) {
	group = "sidecar"
	description = "Type-check + esbuild bundle into src/main/resources/sidecar"
	dependsOn(sidecarInstall)
	workingDir = file("sidecar")
	inputs.dir("sidecar/src")
	inputs.files("sidecar/package.json", "sidecar/tsconfig.json")
	outputs.dir(layout.projectDirectory.dir("sidecar/dist"))
	commandLine(npm("run", "build"))
	doLast {
		copy {
			from("sidecar/dist/sidecar.mjs")
			into("src/main/resources/sidecar")
		}
	}
}

if (!project.hasProperty("skipSidecar")) {
	tasks.named("processResources") { dependsOn(sidecarBuild) }
}
