For my blog/website i used this theme that i modified :

<h3 align="center"><a href="https://github.com/riggraz/no-style-please"># no style, please!</a></h3>



## License

The theme is available as open source under the terms of the [MIT License](https://opensource.org/licenses/MIT).

Run this to run the application in the hotreload 

bundle exec jekyll serve --livereload --livereload-port 35730
or 
bundle exec jekyll serve --livereload --livereload-port 35730 --host 0.0.0.0

make run the hotreload in the different port this is make the conflict with the java file 
     ruby      62753 apple    6u  IPv4 0x3daa95e762da0692      0t0  TCP 127.0.0.1:4000 (LISTEN)
     ruby      62753 apple   10u  IPv4 0xc0f4ab8075eaa23b      0t0  TCP 127.0.0.1:35729 (LISTEN)
     java      81449 apple  142u  IPv6 0x8cb20d1ba3bf4344      0t0  TCP *:35729 (LISTEN)


