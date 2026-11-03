ENV ?= dev
NS := channels-$(ENV)
TAG ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo local)
IMAGE := channel-web/channel-web:$(TAG)
CLUSTER ?= cards-pilot
TMP ?= $(or $(TMPDIR),/tmp)

.PHONY: verify build image load secrets deploy

verify: ## Build the SPA and the BFF and run every test (Docker required)
	./mvnw -B verify

build:
	./mvnw -B -q package -DskipTests

image: build
	docker build -f docker/Dockerfile --build-arg REVISION=$(TAG) -t $(IMAGE) .

load: image ## Load the image into kind (docker save + image-archive is reliable on Colima)
	docker save $(IMAGE) -o $(TMP)/channel-web.tar
	kind load image-archive $(TMP)/channel-web.tar --name $(CLUSTER)
	rm -f $(TMP)/channel-web.tar

secrets: ## Create the channel's secret if missing (never rotates); the client secret comes from the IAM team's store
	@kubectl create namespace $(NS) --dry-run=client -o yaml | kubectl apply -f - >/dev/null
	@kubectl -n $(NS) get secret channel-web-secrets >/dev/null 2>&1 || { \
	  client=$$(kubectl -n iam get secret iam-secrets -o jsonpath='{.data.channel-web-$(ENV)-secret}' | base64 -d); \
	  [ -n "$$client" ] || { echo "no channel-web-$(ENV)-secret in iam/iam-secrets: run make iam in the platform repo" >&2; exit 1; }; \
	  kubectl -n $(NS) create secret generic channel-web-secrets --from-literal=client-secret="$$client" \
	    --from-literal=customer-key-secret="$$(openssl rand -hex 32)" --from-literal=draft-key="$$(openssl rand -base64 32)" \
	    --from-literal=db-password="$$(openssl rand -hex 16)" >/dev/null && echo "created channel-web-secrets in $(NS)"; }

deploy: secrets ## Deploy to channels-ENV (image must be loaded first)
	helm upgrade --install channel-web chart/channel-web -n $(NS) -f environments/$(ENV).yaml --set image.tag=$(TAG) --wait --timeout 6m
