import express from 'express';
import basicAuth from 'express-basic-auth';
import blueskyApi from './bluesky-api';
import morgan from 'morgan';

const app = express();
const port = 3000;

const auth = (() => {
  const user = process.env.SERVER_AUTH_USERNAME;
  if (!user) throw new Error('SERVER_AUTH_USERNAME is not set');
  const pass = process.env.SERVER_AUTH_PASSWORD;
  if (!pass) throw new Error('SERVER_AUTH_PASSWORD is not set');
  return basicAuth({
    users: { [user]: pass },
    challenge: true,
  })
})()

// This will log requests to the console
app.use(morgan('combined'));
// This will parse application/x-www-form-urlencoded bodies
app.use(express.urlencoded({ extended: true }));

app.get('/', (req, res) => {
  res.send('Hello World!');
});

app.post('/bluesky/post', auth, async (req, res) => {
  if (!req.body["content"]) {
    res.status(400).send("Bad Request: Missing content!");
    return;
  }
  const id = await blueskyApi.createPost({
    content: req.body["content"],
    langs: req.body["langs"] ? req.body["langs"].split(",") : undefined,
  });
  console.log(id)
  res.send("OK");
});

app.listen(port, () => {
  console.log(`Server is running at http://localhost:${port}`);
});
