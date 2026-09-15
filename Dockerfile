FROM node:20-alpine
WORKDIR /app
COPY server/package*.json ./
RUN npm install --production
COPY server/server.js ./
ENV PORT=8080
EXPOSE 8080
CMD ["node", "server.js"]

