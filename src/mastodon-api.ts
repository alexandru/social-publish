import axios from 'axios'
import qs from 'qs'

const service = (() => {
    const service = process.env.MASTODON_HOST || 'https://mastodon.social'
    if (!service) throw new Error('MASTODON_HOST is not set')
    return service
})()

const accessToken = (() => {
    const token = process.env.MASTODON_ACCESS_TOKEN
    if (!token) throw new Error('MASTODON_ACCESS_TOKEN is not set')
    return token
})()

async function createPost(post: {
    content: string
}): Promise<string> {
    const url = `${service}/api/v1/statuses?access_token=YOUR_KEY_HERE`
    const config = {
        headers: {
            "Authorization": `Bearer ${accessToken}`,
            "Content-Type": "application/x-www-form-urlencoded"
        }
    }
    const data = {
        status: post.content
    }
    const response = await axios.post(url, qs.stringify(data), config)
    if (response.status < 200 || response.status >= 300) {
        console.error(`Failed to create post — HTTP ${response.status}`, response.data)
    }
    return response.data["url"]
}

export default {
    createPost
}