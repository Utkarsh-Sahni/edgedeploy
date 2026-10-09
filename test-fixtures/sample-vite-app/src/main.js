import "./style.css";

const builtAt = new Date(__BUILD_TIME__).toLocaleString();

document.querySelector("#app").innerHTML = `
  <h1>▲ Deployed with EdgeDeploy</h1>
  <p>This page was built by the EdgeDeploy worker from a GitHub commit.</p>
  <p class="meta">Built ${builtAt} · current route <code>${window.location.pathname}</code></p>
`;
