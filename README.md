# quarkus-mesh-test

Minimal Quarkus app for testing sidecar injection / mTLS behavior against
Red Hat OpenShift Service Mesh 2.6.

## Endpoints

- `GET /hello` - plain text sanity check
- `GET /info` - JSON with pod hostname + timestamp, useful for confirming
  which pod handled a request and eyeballing whether traffic went through
  the Envoy sidecar
- `GET /q/health/live` and `/q/health/ready` - probes

## Build (local, JVM mode)

```bash
mvn clean package
docker build -f src/main/docker/Dockerfile.jvm -t quarkus-mesh-test:latest .
```

## Build directly on OpenShift — three options

### Option A: Docker strategy, binary input (uses Dockerfile.jvm)

```bash
oc new-project quarkus-mesh-test
oc delete bc,is,build -l build=quarkus-mesh-test --ignore-not-found   # clean slate
oc new-build --name=quarkus-mesh-test --binary --strategy=docker -l app=quarkus-mesh-test
mvn clean package
oc start-build quarkus-mesh-test --from-dir=. --follow
```
`--strategy=docker` is required — without it `oc new-build --binary` has no
way to know it should use the Dockerfile. By default it looks for a file
named `Dockerfile` at the repo root; since ours lives at
`src/main/docker/Dockerfile.jvm`, either copy/symlink it to the root as
`Dockerfile`, or patch the BuildConfig's `spec.strategy.dockerStrategy.dockerfilePath`
to point at it.

### Option B: S2I strategy, binary input (uses the UBI OpenJDK builder image, no Dockerfile)

```bash
oc new-project quarkus-mesh-test
oc delete bc,is,build -l build=quarkus-mesh-test --ignore-not-found   # clean slate
oc new-build registry.access.redhat.com/ubi9/openjdk-17:1.20 --binary --name=quarkus-mesh-test
mvn clean package
oc start-build quarkus-mesh-test --from-dir=target/quarkus-app --follow
```
Only `target/quarkus-app` gets uploaded here — the builder image's own
assemble script handles the runtime layout, so it doesn't need your `pom.xml`
or Dockerfile.

If either option fails with `InvalidOutputReference: Output image could not
be resolved`, check whether the cluster's internal image registry is actually
running (`oc get pods -n openshift-image-registry` and
`oc get configs.imageregistry.operator.openshift.io cluster -o jsonpath='{.spec.managementState}'`).
If it's `Removed`/not deployed, build locally with podman/docker and push to
an external registry (quay.io, etc.) instead, then point `k8s/03-app.yaml`'s
`image:` at that external reference.

### Option C: S2I strategy, Git source (uses `oc new-app`, matches the `image~git-url` pattern)

This is the source workflow — OpenShift pulls straight from your Git repo and
sets up an image-change trigger, so future pushes can auto-rebuild/redeploy.
It requires the `.s2i/environment` file already included in this project,
which tells the Java S2I builder how to handle Quarkus's fast-jar layout —
without it, the builder's default assemble script looks for a single jar
under `target/` and won't find a runnable entry point, since fast-jar splits
the app across `lib/`, `app/`, `quarkus/`, and `quarkus-run.jar`.

```bash
# push this project to your own GitHub repo first, then:
oc new-project quarkus-mesh-test
oc new-app registry.redhat.io/ubi8/openjdk-17:latest~https://github.com/<you>/quarkus-mesh-test.git --name=quarkus-mesh-test
oc logs -f bc/quarkus-mesh-test
```
`oc new-app` (unlike `oc new-build`) also creates the Service and a
Deployment automatically — you likely won't need `k8s/03-app.yaml`'s
Deployment/Service, just add the `sidecar.istio.io/inject` annotation to the
pod template it creates, and still apply the Route/mesh manifests
separately.

## Deploy

Manifests are in `k8s/`, applied in order:

```bash
oc apply -f k8s/03-app.yaml   # namespace + deployment + service + route
oc apply -f k8s/01-smcp.yaml  # ServiceMeshControlPlane (istio-system)
oc apply -f k8s/02-smm.yaml   # enrolls quarkus-mesh-test into the mesh
```

Before applying `01-smcp.yaml`, confirm your operator subscription actually
resolved to a 2.6.x CSV:

```bash
oc get csv -n openshift-operators | grep servicemesh
```

If this cluster/namespace ever ran OSSM 3 (Sail Operator) previously, check
for leftover CRs before enrolling:

```bash
oc get istio,istiocni -A
```

## Verifying the sidecar actually injected

```bash
oc get pods -n quarkus-mesh-test
# READY should show 2/2 (app container + istio-proxy)

oc get pod -n quarkus-mesh-test -l app=quarkus-mesh-test -o jsonpath='{.items[0].spec.containers[*].name}'
```

## Notes

- `image` in `03-app.yaml` points at the internal registry path produced by
  an `oc new-build`/`BuildConfig` flow. Adjust if you're pushing to an
  external registry instead.
- This is deliberately bare-bones (no auth, no TLS termination config) - the
  point is to exercise SMCP reconciliation and sidecar injection on 2.6, not
  to be a production workload.
