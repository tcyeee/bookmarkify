import express from "express";

const app = express();

const metascraper = require("metascraper")([
  require("metascraper-amazon")(),
  require("metascraper-audio")(),
  require("metascraper-author")(),
  require("metascraper-date")(),
  require("metascraper-description")(),
  require("metascraper-feed")(),
  require("metascraper-iframe")(),
  require("metascraper-image")(),
  require("metascraper-instagram")(),
  require("metascraper-lang")(),
  require("metascraper-logo")(),
  require("metascraper-logo-favicon")(),
  require("metascraper-manifest")(),
  require("metascraper-media-provider")(),
  require("metascraper-publisher")(),
  require("metascraper-readability")(),
  require("metascraper-soundcloud")(),
  require("metascraper-spotify")(),
  require("metascraper-telegram")(),
  require("metascraper-title")(),
  require("metascraper-uol")(),
  require("metascraper-url")(),
  require("metascraper-video")(),
  require("metascraper-x")(),
  require("metascraper-youtube")(),
]);

app.get("/", (req, res) => {
  const url = req.query.url as string;

  res.send("Hello Express + TypeScript");
});

app.listen(3001, () => {
  console.log("Server started on port 3001");
});
