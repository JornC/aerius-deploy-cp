docker-compose build
docker-compose up -d

echo "Replacing IPs.."

IPGeoserver=$(docker ps -a | grep calculator-geoserver:{{service.SCENARIO_BASE_GEOSERVER.hash}} | cut -d ' ' -f 1 | xargs docker inspect | grep IPAddress | tail -1 | cut -d ':' -f 2 | cut -d '"' -f 2)
IPCalculator=$(docker ps -a | grep calculator-wui:{{service.CALCULATOR_WUI.hash}} | cut -d ' ' -f 1 | xargs docker inspect | grep IPAddress | tail -1 | cut -d ':' -f 2 | cut -d '"' -f 2)

echo "IPCalculator is:"
echo $IPCalculator
echo "IPGeoserver is:"
echo $IPGeoserver

replaceCalculator="sed -i -- 's/{{host.calculator.ip}}/${IPCalculator}/g' site.conf"
replaceGeoserver="sed -i -- 's/{{host.geoserver.ip}}/${IPGeoserver}/g' site.conf"

eval $replaceCalculator
eval $replaceGeoserver

echo "Replacements complete."

cp site.conf /etc/apache2/sites-available/{{cp.pr.id}}.conf
a2ensite {{cp.pr.id}}
service apache2 reload

echo "Site enabled"
