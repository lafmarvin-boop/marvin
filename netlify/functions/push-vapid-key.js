const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  return {
    statusCode: 200,
    headers: CORS,
    body: JSON.stringify({ key: process.env.VAPID_PUBLIC_KEY || '' })
  };
};
