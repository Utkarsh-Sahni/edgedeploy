package com.edgedeploy.build;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DockerfileGenerator {

    public void ensureDockerfile(Path projectDir, String framework) throws Exception {
        Path dockerfile = projectDir.resolve("Dockerfile");
        if (Files.exists(dockerfile)) {
            return;
        }
        Files.writeString(dockerfile, contentFor(framework));
    }

    String contentFor(String framework) {
        return switch (framework) {
            case "nextjs" -> """
                    FROM node:20-alpine AS deps
                    WORKDIR /app
                    COPY package*.json ./
                    RUN npm ci || npm install
                    
                    FROM node:20-alpine AS build
                    WORKDIR /app
                    COPY --from=deps /app/node_modules ./node_modules
                    COPY . .
                    RUN npm run build
                    
                    FROM node:20-alpine
                    WORKDIR /app
                    ENV NODE_ENV=production PORT=3000
                    COPY --from=build /app ./
                    EXPOSE 3000
                    CMD ["npm", "run", "start"]
                    """;
            case "react-vite" -> """
                    FROM node:20-alpine AS build
                    WORKDIR /app
                    COPY package*.json ./
                    RUN npm ci || npm install
                    COPY . .
                    RUN npm run build
                    
                    FROM nginx:1.27-alpine
                    COPY --from=build /app/dist /usr/share/nginx/html
                    EXPOSE 80
                    CMD ["nginx", "-g", "daemon off;"]
                    """;
            default -> """
                    FROM node:20-alpine
                    WORKDIR /app
                    COPY package*.json ./
                    RUN npm ci || npm install
                    COPY . .
                    ENV NODE_ENV=production PORT=3000
                    EXPOSE 3000
                    CMD ["npm", "start"]
                    """;
        };
    }
}
