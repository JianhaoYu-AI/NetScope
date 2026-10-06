FROM node:24-bookworm-slim
WORKDIR /app
COPY --chown=node:node server.js access-policy.js ./
USER node
ENV NETSCOPE_DEPLOYMENT_MODE=protected NETSCOPE_BACKEND_HOST=0.0.0.0 NETSCOPE_BACKEND_PORT=8787
EXPOSE 8787
CMD ["node", "server.js"]
