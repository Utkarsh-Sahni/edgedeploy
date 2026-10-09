# EdgeDeploy sample: Vite app

A minimal static Vite app for exercising the EdgeDeploy build pipeline (clone -> detect VITE ->
generate Dockerfile -> docker build). It has a `package-lock.json`, so EdgeDeploy installs with `npm ci`.

Push it to a GitHub repository of your own to deploy it (see the repository root README):

```bash
cp -R test-fixtures/sample-vite-app /tmp/edgedeploy-sample && cd /tmp/edgedeploy-sample
git init -b main && git add . && git commit -m "Sample app"
gh repo create edgedeploy-sample --public --source . --push
```

Run it locally: `npm ci && npm run dev`.
