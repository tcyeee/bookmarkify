import express from "express";
import got from "got";

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

const scrapeMetaData = async (targetUrl: string) => {
  const { body: html, url } = await got(targetUrl);
  const metadata = await metascraper({ html, url });
  console.log(metadata);
  return metadata;
};

app.get("/", (req, res) => {
  const targetUrl = req.query.url as string;

  scrapeMetaData(targetUrl).then((metadata) => {
    console.log(metadata);
    res.send(metadata);
  });
  res.send("Hello Express + TypeScript");
});

app.listen(3001, () => {
  console.log("Server started on port 3001");
});
