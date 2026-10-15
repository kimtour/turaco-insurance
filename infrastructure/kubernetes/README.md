# Synthetic Kubernetes rehearsal

This bundle deploys PostgreSQL, a local Keycloak realm, Java API, Python extraction/conversation service, independent provider simulator and dashboard. It is a local test environment with synthetic seeds, ClusterIP services and namespace ingress isolation. It is not production infrastructure or a reviewed country/data-residency deployment.

Generate/validate with `python3 scripts/render-kubernetes.py --check` and `kubectl kustomize infrastructure/kubernetes`. The generator reads the canonical local realm fixture; manifests contain Secret references rather than generated secrets. Pods drop capabilities, disable service-account token mounting and run without root. Keycloak's development augmentation requires a writable root filesystem; that fixture exception is explicit and must not be carried into a production identity deployment.

CI builds the application images, loads them and PostgreSQL/Keycloak into a local kind cluster, creates secrets from the job's random local `.env`, deploys the bundle, waits for readiness and runs the authenticated demos through loopback port forwards. No cloud account or external insurance endpoint is involved.

Manual rehearsal with a healthy Docker engine and kind:

```sh
python3 scripts/setup-local.py
docker compose build
docker compose pull postgres keycloak
kind create cluster --name turaco
kind load docker-image --name turaco turaco-insurance-insurance-api:latest turaco-insurance-ai-service:latest turaco-insurance-provider-simulator:latest turaco-insurance-claims-dashboard:latest postgres:17.11-alpine quay.io/keycloak/keycloak:26.4.0
kubectl create namespace turaco-synthetic
python3 scripts/kube-secrets.py --context kind-turaco
kubectl apply -k infrastructure/kubernetes
kubectl -n turaco-synthetic rollout status deployment/insurance-api --timeout=300s
```

Forward insurance-api 8080:8080, keycloak 8081:8080, ai-service 8000:8000, provider-simulator 9000:9000 and claims-dashboard 5173:8080 in separate terminals, then run the README demos. Stop other local stacks first to avoid port conflicts. Remove the disposable cluster with `kind delete cluster --name turaco` when finished.

An AWS/EKS deployment requires an explicitly chosen account, region/residency policy, managed database, secrets/identity, TLS/ingress, backup and cost review. No infrastructure is created in an unknown account, and no unvalidated simulated integration is enabled for real data.
