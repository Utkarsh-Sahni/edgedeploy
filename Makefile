# EdgeDeploy developer commands. Requires JDK 21 (JAVA_HOME), Node 20+, Docker.
.PHONY: help infra-up infra-down infra-reset build test test-docker-build api worker web web-install smoke

help: ## Show available targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

infra-up: ## Start Postgres, Redis, Kafka and Kafka UI
	docker compose up -d --wait

infra-down: ## Stop local infrastructure (data is kept)
	docker compose down

infra-reset: ## Stop local infrastructure and delete its volumes
	docker compose down -v

build: ## Compile and install all JVM modules (skips tests)
	./mvnw -q -B install -DskipTests

test: ## Run all JVM tests (Testcontainers ITs need Docker)
	./mvnw -B verify

test-docker-build: ## Real docker build of test-fixtures/sample-vite-app (needs Docker + network)
	EDGEDEPLOY_DOCKER_E2E=true ./mvnw -B -pl apps/worker -am test -Dtest=DockerBuildE2ETest -Dsurefire.failIfNoSpecifiedTests=false

api: build ## Run the API on :8080
	./mvnw -q -f apps/api/pom.xml spring-boot:run

worker: build ## Run the worker (actuator on :8081)
	./mvnw -q -f apps/worker/pom.xml spring-boot:run

web-install: ## Install web dependencies
	cd apps/web && npm install

web: ## Run the Next.js dashboard on :3000
	cd apps/web && npm run dev

smoke: ## End-to-end check: API -> Kafka -> worker -> RUNNING
	./scripts/smoke-test.sh
