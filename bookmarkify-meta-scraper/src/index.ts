import express from "express";
import got from "got";

const app = express();
const metascraper = require("metascraper")([
  // require("metascraper-audio")(),
  // require("metascraper-author")(),
  // require("metascraper-date")(),
  require("metascraper-description")(),
  // require("metascraper-feed")(),
  // require("metascraper-iframe")(),
  require("metascraper-image")(),
  require("metascraper-lang")(),
  require("metascraper-logo")(),
  require("metascraper-logo-favicon")(),
  // require("metascraper-media-provider")(),
  // require("metascraper-publisher")(),
  // require("metascraper-readability")(),
  require("metascraper-title")(),
  require("metascraper-url")(),
  // require("metascraper-video")(),

  // require("metascraper-amazon")(),
  // require("metascraper-instagram")(),
  // require("metascraper-manifest")(),
  // require("metascraper-soundcloud")(),
  // require("metascraper-spotify")(),
  // require("metascraper-telegram")(),
  // require("metascraper-uol")(),
  // require("metascraper-x")(),
  // require("metascraper-youtube")(),
]);

const scrapeMetaData = async (targetUrl: string) => {
  const { body: html, url } = await got(targetUrl);
  const metadata = await metascraper({ html, url });
  console.log(metadata);
  return metadata;
};

/**
 * 获取目标网站中的mate信息
 */
app.get("/", async (req, res) => {
  const targetUrl = req.query.url as string;

  if (!targetUrl)
    return res.status(400).send({ error: "Missing url parameter" });

  try {
    const metadata = await scrapeMetaData(targetUrl);
    return res.send(metadata);
  } catch (err: any) {
    console.error(err);
    return res.status(500).send({ error: "Failed to scrape metadata" });
  }
});

app.listen(3001, () => {
  console.log("Server started on port 3001");
});
