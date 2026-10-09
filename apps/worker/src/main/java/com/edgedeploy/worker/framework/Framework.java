package com.edgedeploy.worker.framework;

/** Frameworks the worker can build. Mirrors the api's project framework values, minus UNKNOWN. */
public enum Framework {
    /** Create React App (react-scripts): static output in {@code build/}. */
    REACT,
    /** Vite (React, Vue, Svelte, vanilla...): static output in {@code dist/}. */
    VITE,
    NEXTJS,
    /** A Node.js server started with {@code npm start} or its {@code main} file. */
    NODE
}
