const { chromium } = require('playwright');
(async () => {
  const dir = process.argv[2];
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  for (let i = 0; i < 5; i++) {
    await p.goto('file://' + dir + '/store.html?i=' + i);
    await p.evaluate(() => document.fonts.ready);
    await p.waitForTimeout(400);
    await p.locator('.shot').screenshot({ path: dir + '/out/phone_' + (i + 1) + '.png' });
  }
  await p.setViewportSize({ width: 1024, height: 500 });
  await p.goto('file://' + dir + '/store.html?f=1');
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(400);
  await p.locator('.feature').screenshot({ path: dir + '/out/feature_graphic.png' });
  console.log('fonts:', await p.evaluate(() => [...document.fonts].filter(f => f.status === 'loaded').length));
  await b.close();
})();
